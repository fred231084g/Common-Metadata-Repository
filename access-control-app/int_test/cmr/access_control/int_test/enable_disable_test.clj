(ns cmr.access-control.int-test.enable-disable-test
  "CMR Ingest Enable/Disable endpoint test"
  (:require
    [cheshire.core :as json]
    [clj-http.client :as client]
    [clojure.string :as str]
    [clojure.test :refer :all]
    [cmr.access-control.int-test.fixtures :as fixtures]
    [cmr.access-control.test.util :as u]
    [cmr.common.util :as util :refer [are3]]
    [cmr.mock-echo.client.echo-util :as e]
    [cmr.transmit.access-control :as ac]
    [cmr.transmit.config :as transmit-config]
    [cmr.transmit.metadata-db2 :as mdb]))


(use-fixtures :each
              (fixtures/int-test-fixtures)
              (fixtures/reset-fixture {"prov1guid" "PROV1" "prov2guid" "PROV2"}
                                      ["user1" "user2" "user3" "user4" "user5"])
              (fixtures/grant-all-group-fixture ["prov1guid" "prov2guid"])
              (fixtures/grant-all-acl-fixture))

(def system-acl
  "A system ingest management acl that grants read and update to guest users"
  {:group_permissions [{:user_type "guest"
                        :permissions ["read" "update"]}]
   :system_identity {:target "INGEST_MANAGEMENT_ACL"}})

(def post-options
  "Options map to pass on POST requests to enable/disable writes in access control."
  {:headers {transmit-config/token-header (transmit-config/echo-system-token)}})

(deftest enable-disable-enable-write-acl
  (testing "save and delete acl works before disable"
    (let [token (e/login (u/conn-context) "admin")
          resp (ac/create-acl (u/conn-context) system-acl {:raw? true :token token})
          concept-id (get-in resp [:body :concept_id])]
      (is (= 200 (:status resp)))

      (is (= 200 (:status (ac/delete-acl (u/conn-context) concept-id {:token token :raw? true}))))))

  (u/disable-access-control-writes post-options))

