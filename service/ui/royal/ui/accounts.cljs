(ns royal.ui.accounts (:require [uix.core :refer [defui $]] [royal.ui.components :as c :refer [icon badge]]))

(defui account-picker [{:keys [personas choose busy pending-id error]}]
  ($ :main {:class "account-screen"}
     ($ :header {:class "account-brand"} ($ :div {:class "brand"} ($ icon {:name :document :size 23}) ($ :span "명문가")) ($ badge "시연 계정"))
     ($ :section {:class "account-content"}
        ($ :h1 "누구로 시작할까요?")
        ($ :p {:class "account-intro muted"} "계정 아래에서 시연할 업무를 확인하세요.")
        ($ :div {:class "account-grid"}
           (for [persona personas]
             ($ :button {:key (:id persona) :class "account-card" :disabled busy :aria-busy (= pending-id (:id persona)) :on-click #(choose persona)}
                ($ :div {:class "account-identity"} ($ :span {:class "avatar"} (subs (:name persona) 0 1))
                   ($ :strong (:name persona)) ($ :span {:class "account-role"} (:role persona))
                   (if (= pending-id (:id persona)) ($ c/spinner) ($ icon {:name :chevron :size 16})))
                ($ :span {:class "account-scenario"} (:scenario persona)))))
        (when error ($ :p {:class "inline-warning" :role "alert"} error))
        ($ :p {:class "account-footnote muted small"} "가상 자료로 같은 관리 기능을 시연합니다. 실제 종원 인증이 아닙니다."))))
