(ns vetd-admin.admin
  (:require [vetd-app.hooks :as hooks]
            [vetd-admin.pages.a-search :as p-a-search]
            [vetd-admin.pages.form-templates :as p-aform-templates]
            [vetd-admin.pages.stack-renewal-reminders :as p-astack-renewal-reminders]
            [vetd-admin.admin-fixtures :as a-fix]
            [vetd-admin.overlays.admin-v-preposals :as ovr-v-preposals]
            [vetd-admin.overlays.admin-b-round-detail :as ovr-b-round-detail]
            [vetd-admin.overlays.admin-c-settings :as ovr-c-settings]
            [vetd-admin.overlays.admin-settings :as ovr-settings]            
            [reagent.core :as r]
            [re-frame.core :as rf]
            [re-com.core :as rc]
            [secretary.core :as sec]))

(println "START ADMIN")

(def show-admin?& (r/atom false))

(sec/defroute admin-search-path "/a/search" []
  (rf/dispatch [:a/route-search]))

(sec/defroute admin-form-templates-path "/a/form-templates" []
  (rf/dispatch [:a/route-form-templates nil]))

(sec/defroute admin-form-templates-id-path "/a/form-templates/:idstr" [idstr]
  (rf/dispatch [:a/route-form-templates idstr]))

(sec/defroute admin-stack-renewal-reminders-path "/a/stack-renewal-reminders" []
  (rf/dispatch [:a/route-stack-renewal-reminders]))

(defn c-admin-buyer []
  [:div "ADMIN BUYER"])

(defn c-admin-overlay-container [p]
  (fn [p]
    (if-not @show-admin?&
      [:div#admin-over-cont.closed
       [:div#admin-symbol
        {:on-click #(reset! show-admin?& true)}]]
      [:div#admin-over-cont.open
       p
       [:div#admin-symbol
        {:on-click #(reset! show-admin?& false)}]])))

(defn c-admin-container [p]
  [:div "ADMIN CONTAINER " p])

(defn init! []
  (println "init! ADMIN"))

(hooks/reg-hooks! hooks/c-page
                  {:a/search #'p-a-search/c-page
                   :a/form-templates #'p-aform-templates/c-page
                   :a/stack-renewal-reminders #'p-astack-renewal-reminders/c-page})

(hooks/reg-hooks! hooks/c-container
                  {:admin-overlay #'c-admin-overlay-container
                   :a/search #'a-fix/container
                   :a/form-templates #'a-fix/container
                   :a/stack-renewal-reminders #'a-fix/container})

(hooks/reg-hook! hooks/init! :admin init!)

(hooks/reg-hook! hooks/c-admin :v/preposals ovr-v-preposals/c-overlay)
(hooks/reg-hook! hooks/c-admin :b/round-detail ovr-b-round-detail/c-overlay)
(hooks/reg-hook! hooks/c-admin :g/settings ovr-c-settings/c-overlay)
(hooks/reg-hook! hooks/c-admin :settings ovr-settings/c-overlay)


(println "END ADMIN")
