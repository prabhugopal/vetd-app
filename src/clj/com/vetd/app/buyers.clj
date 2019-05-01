(ns com.vetd.app.buyers
  (:require [com.vetd.app.db :as db]
            [com.vetd.app.hasura :as ha]
            [com.vetd.app.auth :as auth]
            [com.vetd.app.common :as com]
            [com.vetd.app.util :as ut]
            [com.vetd.app.docs :as docs]
            [taoensso.timbre :as log]))

(defn product-id->name
  [product-id]
  (-> [[:products {:id product-id}
        [:pname]]]
      ha/sync-query
      vals
      ffirst
      :pname))

(defn search-prods-vendors->ids
  [q]
  (if (not-empty q)
    (let [ids (db/hs-query {:select [[:p.id :pid] [:o.id :vid]]
                            :from [[:products :p]]
                            :join [[:orgs :o] [:= :o.id :p.vendor_id]]
                            :where [:or
                                    [(keyword "~*") :p.pname (str ".*?" q ".*")]
                                    [(keyword "~*") :o.oname (str ".*?" q ".*")]]
                            :limit 30})
          pids (map :pid ids)
          vids (->> ids
                    (map :vid)
                    distinct)]
      {:product-ids pids
       :vendor-ids vids})
    {:product-ids []
     :vendor-ids []}))

(defn search-category-ids
  [q]
  (if (not-empty q)
    (mapv :id
          (db/hs-query {:select [:id]
                        :from [:categories]
                        :where [(keyword "~*") :cname (str ".*?" q ".*")]
                        :limit 5}))))

(defn select-rounds-by-ids
  [b-id v-ids]
  (db/hs-query {:select [:*]
                :from [:rounds]
                :where [:and
                        [:= :buyer-id b-id]
                        [:in :vendor-id v-ids]]}))

(defn insert-preposal-req
  [buyer-id vendor-id]
  (db/insert! :preposal_reqs
              {:id (ut/uuid-str)
               :buyer-id buyer-id
               :vendor-id vendor-id
               :created (ut/now)}))

#_(select-prep-reqs-by-ids 3 [1 2 3 4])

(defn invert-vendor-data
  [m]
  (let [m1 (ut/fmap #(group-by (some-fn :vendor-id :id) %)
                    m)
        paths (for [[k v] m1
                    [k2 v2] v]
                [[k2 k] (first v2)])]
    (reduce (fn [agg [ks v]]
              (assoc-in agg ks v))
            {}
            paths)))

(defn insert-round
  [buyer-id title]
  (let [[id idstr] (ut/mk-id&str)]
    (-> (db/insert! :rounds
                    {:id id
                     :idstr idstr
                     :buyer_id buyer-id
                     :status "initiation"
                     :title title
                     :created (ut/now-ts)
                     :updated (ut/now-ts)})
        first)))

(defn insert-round-product
  [round-id prod-id]
  (let [[id idstr] (ut/mk-id&str)]
    (-> (db/insert! :round_product
                    {:id id
                     :idstr idstr
                     :round_id round-id
                     :product_id prod-id
                     :created (ut/now-ts)
                     :updated (ut/now-ts)})
        first)))

(defn insert-round-category
  [round-id category-id]
  (let [[id idstr] (ut/mk-id&str)]
    (-> (db/insert! :round_category
                    {:id id
                     :idstr idstr
                     :round_id round-id
                     :category_id category-id
                     :created (ut/now-ts)
                     :updated (ut/now-ts)})
        first)))

(defn create-round
  [buyer-id title eid etype]
  (let [{:keys [id] :as r} (insert-round buyer-id title)]
    (case etype
      :product (insert-round-product id eid)
      :category (insert-round-category id eid))
    (try
      (let [msg (with-out-str
                  (clojure.pprint/with-pprint-dispatch clojure.pprint/code-dispatch
                    (-> [[:rounds {:id id}
                          [:id :created
                           [:buyer [:oname]]
                           [:products [:pname]]
                           [:categories [:cname]]]]]
                        ha/sync-query
                        vals
                        ffirst
                        clojure.pprint/pprint)))]
        ;; TODO make msg human friendly
        (com/sns-publish :ui-start-round "Vetd Round Started" msg))
      (catch Throwable t))
    r))

(defn send-new-prod-cat-req [uid oid req]
  (let [user-name (-> uid auth/select-user-by-id :uname)
        org-name (-> oid auth/select-org-by-id :oname)]
    (com/sns-publish :ui-req-new-prod-cat
                     "New Product/Category Request"
                     (format
                      "New Product/Category Request
Request Text '%s'
Org '%s'
User '%s'
"
                      req org-name user-name))))

(defn send-complete-profile-req [etype eid field-key buyer-id]
  (let [ename (if (= :vendor etype)
                (-> eid auth/select-org-by-id :oname)
                (product-id->name eid))
        buyer-name (-> buyer-id auth/select-org-by-id :oname)]
    (com/sns-publish :ui-misc
                     (str "Complete " (name etype) " Profile Request")
                     (str "Complete " (name etype) " Profile Request\n"
                          "buyer: " buyer-name "\n"
                          (name etype) ": " ename " (ID: " eid ")\n"
                          "field name: " field-key))))


(defn send-setup-call-req [buyer-id product-id]
  (com/sns-publish :ui-misc
                   "Setup Call Request"
                   (format
                    "Setup Call Request
Buyer (Org): '%s'
Product: '%s'"
                    (-> buyer-id auth/select-org-by-id :oname) ; buyer name
                    (product-id->name product-id)))) ; product name


(defn send-ask-question-req [product-id message round-id requirement-text buyer-id]
  (com/sns-publish :ui-misc
                   "Ask a Question Request"
                   (str "Ask a Question Request"
                        "\nBuyer (Org): " (-> buyer-id auth/select-org-by-id :oname) ; buyer name
                        "\nProduct: " (product-id->name product-id) ; product name
                        (when round-id (str  "\nRound ID: " round-id))
                        (when requirement-text (str  "\nRequirement: " requirement-text))
                        "\nMessage:\n" message)))

(defn send-prep-req
  [{:keys [to-org-id to-user-id from-org-id from-user-id prod-id] :as prep-req}]
  (com/sns-publish :ui-misc
                   "Preposal Request"
                   (format
                    "Preposal Request
Buyer (Org): '%s'
Buyer User: '%s'

Product: '%s'"
                    (-> from-org-id auth/select-org-by-id :oname) ; buyer org name
                    (-> from-user-id auth/select-user-by-id :uname) ; buyer user name
                    (product-id->name prod-id)))) ; product name

;; TODO there could be multiple preposals/rounds per buyer-vendor pair

;; TODO use session-id to verify permissions!!!!!!!!!!!!!
(defmethod com/handle-ws-inbound :b/search
  [{:keys [buyer-id query]} ws-id sub-fn]
  (-> query
      search-prods-vendors->ids
      (assoc :category-ids
             (search-category-ids query))))

;; Start a round for either a Product or a Category
;; TODO record which user started round
(defmethod com/handle-ws-inbound :b/start-round
  [{:keys [buyer-id title etype eid]} ws-id sub-fn]
  (create-round buyer-id title eid etype))

;; Request Preposal
(defmethod com/handle-ws-inbound :b/create-preposal-req
  [{:keys [prep-req]} ws-id sub-fn]
  (send-prep-req prep-req)
  (docs/create-preposal-req-form prep-req))

;; Request an addition to our Products / Categories
(defmethod com/handle-ws-inbound :b/req-new-prod-cat
  [{:keys [user-id org-id req]} ws-id sub-fn]
  (send-new-prod-cat-req user-id org-id req))

;; Request that a vendor complete their Company/Product Profile
(defmethod com/handle-ws-inbound :b/request-complete-profile
  [{:keys [etype eid field-key buyer-id]} ws-id sub-fn]
  (send-complete-profile-req etype eid field-key buyer-id))

;; Have Vetd set up a phone call for the buyer with the vendor
(defmethod com/handle-ws-inbound :b/setup-call
  [{:keys [buyer-id product-id]} ws-id sub-fn]
  (send-setup-call-req buyer-id product-id))

;; Ask a question about a specific product
(defmethod com/handle-ws-inbound :b/ask-a-question
  [{:keys [product-id message round-id requirement-text buyer-id]} ws-id sub-fn]
  (send-ask-question-req product-id message round-id requirement-text buyer-id))

(defmethod com/handle-ws-inbound :save-doc
  [{:keys [data ftype update-doc-id from-org-id] :as req} ws-id sub-fn]
  (if (nil? update-doc-id)
    (docs/create-doc req)
    (docs/update-doc req)))

;; additional side effects upon creating a round-initiation doc
(defmethod docs/handle-doc-creation :round-initiation
  [{:keys [id]} {:keys [round-id]}]
  (try
    (db/update-any! {:id round-id
                     :doc_id id
                     :status "in-progress"}
                    :rounds)
    (catch Throwable t
      (log/error t)))
  (try
    (let [round (-> [[:docs {:id id}
                      [[:rounds
                        [:id :created
                         [:buyer [:oname]]
                         [:products [:pname]]
                         [:categories [:cname]]
                         [:doc
                          [:id
                           [:response-prompts {:ref-deleted nil}
                            [:id :prompt-id :prompt-prompt :prompt-term
                             [:response-prompt-fields
                              [:id :prompt-field-fname :idx
                               :sval :nval :dval]]]]]]]]]]]
                    ha/sync-query
                    vals
                    ffirst
                    :rounds)]
      (com/sns-publish
       :ui-misc
       "Vendor Round Initiation Form Completed"
       (str "Vendor Round Initiation Form Completed\n\n"
            (str "Buyer (Org): " (-> round :buyer :oname)
                 "\nProducts: " (->> round :products (map :pname) (interpose ", ") (apply str))
                 "\nCategories: " (->> round :categories (map :cname) (interpose ", ") (apply str))
                 "\n-- Form Data --"
                 (apply str
                        (for [rp (-> round :doc :response-prompts)]
                          (str "\n" (:prompt-prompt rp) ": "
                               (->> rp :response-prompt-fields (map :sval) (interpose ", ") (apply str)))))))))
    (catch Throwable t)))


