;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.sidebar.sitemap
  (:require
   [app.common.data :as d]
   [app.main.data.modal :as modal]
   [app.main.data.workspace :as dw]
   [app.main.refs :as refs]
   [app.main.store :as st]
   [app.main.ui.context :as ctx]
   [app.main.ui.hooks :as hooks]
   [app.main.ui.hooks.resize :refer [use-resize-hook]]
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.keyboard :as kbd]
   [cuerdas.core :as str]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

;; --- Page Item

(mf/defc page-item
  [{:keys [page index deletable? selected? editing?] :as props}]
  (let [input-ref            (mf/use-ref)
        id                   (:id page)

        delete-fn            (mf/use-callback (mf/deps id) #(st/emit! (dw/delete-page id)))
        navigate-fn          (mf/use-callback (mf/deps id) #(st/emit! :interrupt (dw/go-to-page id)))
        workspace-read-only? (mf/use-ctx ctx/workspace-read-only?)

        on-delete
        (mf/use-callback
         (mf/deps id)
         #(st/emit! (modal/show
                     {:type :confirm
                      :title (tr "modals.delete-page.title")
                      :message (tr "modals.delete-page.body")
                      :on-accept delete-fn})))

        on-double-click
        (mf/use-callback
         (mf/deps workspace-read-only?)
         (fn [event]
           (dom/prevent-default event)
           (dom/stop-propagation event)
           (when-not workspace-read-only?
             (st/emit! (dw/start-rename-page-item id))
             (st/emit! (dw/hide-context-menu)))))

        on-blur
        (mf/use-callback
         (fn [event]
           (let [target (dom/event->target event)
                 name   (str/trim (dom/get-value target))]
             (when-not (str/empty? name)
               (st/emit! (dw/rename-page id name)))
             (st/emit! (dw/stop-rename-page-item)))))

        on-key-down
        (mf/use-callback
         (fn [event]
           (cond
             (kbd/enter? event)
             (on-blur event)

             (kbd/esc? event)
             (st/emit! (dw/stop-rename-page-item)))))

        on-drop
        (mf/use-callback
         (mf/deps id index)
         (fn [side {:keys [id] :as data}]
           (let [index (if (= :bot side) (inc index) index)]
             (st/emit! (dw/relocate-page id index)))))

        [dprops dref]
        (hooks/use-sortable
         :data-type "penpot/page"
         :on-drop on-drop
         :data {:id id
                :index index
                :name (:name page)}
         :draggable? (not workspace-read-only?))

        on-context-menu
        (mf/use-callback
         (mf/deps id workspace-read-only?)
         (fn [event]
           (dom/prevent-default event)
           (dom/stop-propagation event)
           (when-not workspace-read-only?
             (let [position (dom/get-client-position event)]
               (st/emit! (dw/show-page-item-context-menu 
                          {:position position 
                           :page page 
                           :deletable? deletable?}))))))]

    (mf/use-effect
      (mf/deps selected?)
      (fn []
        (when selected?
          (let [node (mf/ref-val dref)]
            (dom/scroll-into-view-if-needed! node)))))

    (mf/use-layout-effect
     (mf/deps editing?)
     (fn []
       (when editing?
         (let [edit-input (mf/ref-val input-ref)]
           (dom/select-text! edit-input))
         nil)))

    [:*
     [:li {:class (dom/classnames
                   :selected selected?
                   :dnd-over-top (= (:over dprops) :top)
                   :dnd-over-bot (= (:over dprops) :bot))
           :ref dref}
      [:div.element-list-body
       {:class (dom/classnames
                :selected selected?)
        :on-click navigate-fn
        :on-double-click on-double-click
        :on-context-menu on-context-menu}
       [:div.page-icon i/file-html]
       (if editing?
         [:*
          [:input.element-name {:type "text"
                                :ref input-ref
                                :on-blur on-blur
                                :on-key-down on-key-down
                                :auto-focus true
                                :default-value (:name page "")}]]
         [:*
          [:span (:name page)]
          [:div.page-actions
           (when (and deletable? (not workspace-read-only?))
             [:a {:on-click on-delete} i/trash])]])]]]))


;; --- Page Item Wrapper

(defn- make-page-ref
  [page-id]
  (l/derived (fn [state]
               (let [page (get-in state [:workspace-data :pages-index page-id])]
                 (select-keys page [:id :name])))
              st/state =))

(mf/defc page-item-wrapper
  [{:keys [page-id index deletable? selected? editing?] :as props}]
  (let [page-ref (mf/use-memo (mf/deps page-id) #(make-page-ref page-id))
        page     (mf/deref page-ref)]
    [:& page-item {:page page
                   :index index
                   :deletable? deletable?
                   :selected? selected?
                   :editing? editing?}]))

;; --- Pages List

(mf/defc pages-list
  [{:keys [file] :as props}]
  (let [pages           (:pages file)
        deletable?      (> (count pages) 1)
        editing-page-id (mf/deref refs/editing-page-item)
        current-page-id (mf/use-ctx ctx/current-page-id)]
    [:ul.element-list.pages-list
     [:& hooks/sortable-container {}
      (for [[index page-id] (d/enumerate pages)]
        [:& page-item-wrapper
         {:page-id page-id
          :index index
          :deletable? deletable?
          :selected? (= page-id current-page-id)
          :editing? (= page-id editing-page-id)
          :key page-id}])]]))

;; --- Sitemap Toolbox

(mf/defc sitemap
  []
  (let [{:keys [on-pointer-down on-lost-pointer-capture on-pointer-move parent-ref size]}
        (use-resize-hook :sitemap 200 38 400 :y false nil)

        file                 (mf/deref refs/workspace-file)
        create               (mf/use-callback
                              (mf/deps file)
                              (fn []
                                (st/emit! (dw/create-page {:file-id (:id file)
                                                           :project-id (:project-id file)}))))
        show-pages?          (mf/use-state true)
        size                 (if @show-pages? size 38)
        toggle-pages         (mf/use-callback #(reset! show-pages? not))
        workspace-read-only? (mf/use-ctx ctx/workspace-read-only?)]

    [:div#sitemap.tool-window {:ref parent-ref
                               :style #js {"--height" (str size "px")}}
     [:div.tool-window-bar
      [:span (tr "workspace.sidebar.sitemap")]
      (if workspace-read-only?
        [:div.view-only-mode (tr "labels.view-only")]
        [:div.add-page {:on-click create} i/close])
      [:div.collapse-pages {:on-click toggle-pages
                            :style {:transform (when (not @show-pages?) "rotate(-90deg)")}} i/arrow-slide]]

     [:div.tool-window-content
      [:& pages-list {:file file :key (:id file)}]]

     (when @show-pages?
       [:div.resize-area {:on-pointer-down on-pointer-down
                          :on-lost-pointer-capture on-lost-pointer-capture
                          :on-pointer-move on-pointer-move}])]))
