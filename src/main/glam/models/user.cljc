(ns glam.models.user
  (:require [clojure.set :refer [rename-keys]]
            [clojure.spec.alpha :as s]
            [com.fulcrologic.fulcro.mutations :as m]
            [com.fulcrologic.fulcro.components :as c]
            [com.fulcrologic.fulcro.algorithms.form-state :as fs]
            [com.fulcrologic.guardrails.core :refer [>defn => | ?]]
            [com.wsscode.pathom.connect :as pc :refer [defresolver defmutation]]
            #?(:clj [cryptohash-clj.impl.argon2 :refer [chash verify]])
            [taoensso.timbre :as log]
            #?(:clj [glam.models.common :as mc :refer [server-error server-message]])
            #?(:clj [glam.crux.user :as user])
            #?(:clj [glam.crux.easy :as gce])))


;; common --------------------------------------------------------------------------------
(def user-keys [;; a unique email used for user login
                :user/email
                ;; a unique display name. defaults to email on signup
                :user/name
                ;; boolean: user is an admin?
                :user/admin?
                ;; multi-joins to projects users can read/edit
                :user/reader
                :user/writer
                ;; password fields--these keywords are NOT stored in the database but
                ;; need to be present here so forms can rely on it when a new password
                ;; needs to be validated. the crux
                :user/password
                :user/new-password])
(defn valid-password [password] (>= (count password) 8))

(def email-regex #"^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,63}$")
(defn ^boolean valid-email [email]
  (boolean (re-matches email-regex email)))

(defn ^boolean valid-name [name]
  (and (string? name)
       (<= 2 (count name) 40)))

(def valid-admin? boolean?)

(defn valid-reader [reader]
  (and (set? reader)
       (every? uuid? reader)))

(def valid-writer valid-reader)

(defn- field-valid [field v]
  (case field
    :user/name (valid-name v)
    :user/admin? (valid-admin? v)
    :user/email (valid-email v)
    :user/reader (valid-reader v)
    :user/writer (valid-writer v)
    ;; remember that password is a special case: "password-hash" is what is stored,
    ;; but we need to validate passwords themselves
    :user/password (valid-password v)
    :user/new-password (valid-password v)))

(defn user-valid [form field]
  (let [v (get form field)]
    (field-valid field v)))

(defn record-valid? [record]
  (every? (fn [[k v]] (field-valid k v)) record))

(def validator (fs/make-validator user-valid))

;; mutations and resolvers --------------------------------------------------------------------------------
#?(:clj
   (defn hash-password [password]
     (chash password)))

#?(:clj
   (defn verify-password [input hashed]
     (verify input hashed)))

#?(:clj
   (defn get-current-user
     "Reads username (email) from the ring session and returns the ID"
     [{:keys [crux] :ring/keys [request] :as env}]
     (when-let [session (:session request)]
       (when (:session/valid? session)
         (if-let [email (:user/email session)]
           (do (log/info "HAVE A USER: " email)
               (gce/find-entity-id crux {:user/email email}))
           (do (log/info "no user")
               nil))))))

;; user level --------------------------------------------------------------------------------
;; todo: should this only work for the user's own id?
#?(:clj
   (defresolver user-resolver [{:keys [crux]} {:user/keys [id]}]
     {::pc/input     #{:user/id}
      ::pc/output    [:user/email :user/name :user/admin?]
      ::pc/transform mc/user-required}
     (gce/entity crux id)))

#?(:cljs
   (m/defmutation change-own-password
     "Changes the user's password given a :user/email, :current-password, and :new-password.
     on-ok and on-error are lambdas that will be executed when a server response is given
     with any server message passed to it "
     [args]
     (action [{:keys [app]}] (log/info "Beginning change-password"))
     (remote [{:keys [ast]}] true))
   :clj
   (pc/defmutation change-own-password
     [{:keys [crux] :as env} {:keys [current-password new-password]}]
     {::pc/transform mc/user-required}
     (let [id (get-current-user env)
           {:user/keys [password-hash]} (gce/entity crux id)]
       (cond
         ;; user must be valid
         (nil? id)
         (server-error "Invalid session")
         ;; current password must be correct
         (not (verify-password current-password password-hash))
         (server-error (str "Current password incorrect"))
         ;; new password must be valid
         (not (valid-password new-password))
         (server-error "New password is invalid")
         :else
         (do
           (user/merge crux id {:user/password-hash (hash-password new-password)})
           (server-message "Password change successful"))))))

#?(:cljs
   (m/defmutation change-own-name
     "Change :user/name"
     [args]
     (action [{:keys [app]}] (log/info "Begin change name"))
     (remote [{:keys [ast]}] true))
   :clj
   (pc/defmutation change-own-name
     [{:keys [crux] :as env} {:keys [name]}]
     {::pc/transform mc/user-required}
     (let [user-id (get-current-user env)
           same-names (gce/find-entities crux {:user/name name})]
       (cond
         ;; user must be valid
         (nil? user-id)
         (server-error (str "No valid user found while attempting to change name"))
         ;; name must not be taken
         (and (not (empty? same-names)) (not= user-id (-> same-names first :user/id)))
         (server-error (str "Name \"" name "\" already taken"))
         ;; name must be valid
         (not (valid-name name))
         (server-error (str "Name \"" name "\" is invalid"))
         :else
         (do
           (user/merge crux user-id {:user/name name})
           (server-message (str "Name changed to " name)))))))

;; admin level -------------------------------------------------------------------------------
#?(:clj
   (pc/defresolver all-users-resolver [{:keys [crux]} _]
     {::pc/output    [{:all-users [:user/id]}]
      ::pc/transform mc/admin-required}
     {:all-users (user/get-all crux)}))

#?(:clj
   (pc/defmutation delete-user [{:keys [crux]} {[_ id] :ident :as params}]
     {::pc/transform mc/admin-required}
     (if-not (gce/entity crux id)
       (server-error (str "User not found by ID " id))
       (let [name (:user/name (gce/entity crux id))]
         (user/delete crux id)
         (server-message (str "User " name " deleted"))))))

#?(:clj
   (pc/defmutation save-user [{:keys [crux]} {delta :delta [_ id] :ident new-password :user/new-password :as params}]
     {::pc/transform mc/admin-required
      ::pc/output    [:server/error? :server/message]}
     (log/info (str "id:" (:ident params)))
     (cond
       ;; email must be unique if it's being changed
       (and (some-> delta :user/email :after) (gce/find-entity crux {:user/email (-> delta :user/email :after)}))
       (server-error (str "User already exists with email " (-> delta :user/email :after)))
       ;; name must be unique if it's being changed
       (and (some-> delta :user/name :after) (gce/find-entity crux {:user/name (-> delta :user/name :after)}))
       (server-error (str "User already exists with name " (-> delta :user/name :after)))
       ;; must be valid
       (not (mc/validate-delta record-valid? delta))
       (server-error (str "User delta invalid: " delta))
       ;; if password is present, must be valid
       (and (some? new-password) (> (count new-password) 0) (not (valid-password new-password)))
       (server-error (str "New password is invalid"))
       :else
       (do
         (gce/merge crux id (-> (mc/apply-delta {} delta)
                                (cond-> (and (some? new-password) (> (count new-password) 0) (valid-password new-password))
                                        (merge {:user/password-hash (hash-password new-password)}))))
         (gce/entity crux id)))))
#?(:clj
   (pc/defmutation create-user [{:keys [crux]} {delta :delta [_ id] :ident :as params}]
     {::pc/transform mc/admin-required
      ::pc/output    [:server/error? :server/message]}
     (let [{:user/keys [email name password] :as new-user} (-> {} (mc/apply-delta delta) (select-keys user-keys))]
       (cond
         ;; email must be unique
         (gce/find-entity crux {:user/email email})
         (server-error (str "User already exists with email " email))
         ;; name must be unique
         (gce/find-entity crux {:user/name name})
         (server-error (str "User already exists with name " name))
         ;; password must be valid
         (not (valid-password password))
         (server-error (str "Password is invalid"))
         :else
         {:tempids {id (user/create crux (merge new-user {:user/password-hash (hash-password password)}))}}))))



#?(:clj
   (def resolvers [user-resolver
                   all-users-resolver
                   change-own-password
                   change-own-name
                   delete-user
                   save-user
                   create-user]))
