(ns cmr.system-int-test.search.collection-geojson-search-test
  (:require
    [clojure.test :refer :all]
    [clojure.java.io :as io]
    [cmr.common.util :as util :refer [are3]]
    [cmr.common-app.test.side-api :as side]
    [cmr.search.services.parameters.converters.shapefile :as shapefile]
    [cmr.spatial.line-string :as l]
    [cmr.spatial.mbr :as m]
    [cmr.spatial.point :as p]
    [cmr.spatial.polygon :as poly]
    [cmr.system-int-test.data2.core :as d]
    [cmr.system-int-test.data2.granule :as dg]
    [cmr.system-int-test.data2.umm-spec-collection :as data-umm-c]
    [cmr.system-int-test.utils.index-util :as index]
    [cmr.system-int-test.utils.ingest-util :as ingest]
    [cmr.system-int-test.utils.search-util :as search]
    [cmr.umm.umm-spatial :as umm-s]
    [cmr.system-int-test.data2.collection :as dc]))

(use-fixtures :each (ingest/reset-fixture {"provguid1" "PROV1"}))

(defn- polygon
  "Creates a single ring polygon with the given ordinates. Points must be in counter clockwise
  order."
  [& ords]
  (poly/polygon [(apply umm-s/ords->ring ords)]))

(defn- make-coll
  [coord-sys et & shapes]
  (let [shapes (map (partial umm-s/set-coordinate-system coord-sys) shapes)]
    (d/ingest "PROV1"
              (dc/collection
               {:entry-title et
                :spatial-coverage (dc/spatial {:gsr coord-sys
                                               :sr coord-sys
                                               :geometries shapes})}))))

(deftest collection-geojson-search-test
  (let [_ (side/eval-form `(shapefile/set-enable-shapefile-parameter-flag! true))
        ;; Lines
        normal-line (make-coll :geodetic "normal-line"
                               (l/ords->line-string :geodetic [22.681 -8.839, 18.309 -11.426, 22.705 -6.557]))
        along-am-line (make-coll :geodetic "along-am-line"
                                 (l/ords->line-string :geodetic [-180 0 -180 85]))
        normal-line-cart (make-coll :cartesian "normal-line-cart"
                                    (l/ords->line-string :cartesian [16.439 -13.463,  31.904 -13.607, 31.958 -10.401]))

        ;; Bounding rectangles
        whole-world (make-coll :geodetic "whole-world" (m/mbr -180 90 180 -90))
        touches-np (make-coll :geodetic "touches-np" (m/mbr 45 90 55 70))
        touches-sp (make-coll :geodetic "touches-sp" (m/mbr -160 -70 -150 -90))
        across-am-br (make-coll :geodetic "across-am-br" (m/mbr 170 10 -170 -10))
        normal-brs (make-coll :geodetic "normal-brs"
                              (m/mbr 10 10 20 0)
                              (m/mbr -20 0 -10 -10))

        ;; Polygons
        wide-north (make-coll :geodetic "wide-north" (polygon -70 20, 70 20, 70 30, -70 30, -70 20))
        wide-south (make-coll :geodetic "wide-south" (polygon -70 -30, 70 -30, 70 -20, -70 -20, -70 -30))
        across-am-poly (make-coll :geodetic "across-am-poly" (polygon 170 -10, -175 -10, -170 10, 175 10, 170 -10))
        on-np (make-coll :geodetic "on-np" (polygon 45 85, 135 85, -135 85, -45 85, 45 85))
        on-sp (make-coll :geodetic "on-sp" (polygon -45 -85, -135 -85, 135 -85, 45 -85, -45 -85))

        ;; polygon with holes
        outer (umm-s/ords->ring -5.26,-2.59, 11.56,-2.77, 10.47,8.71, -5.86,8.63, -5.26,-2.59)
        hole1 (umm-s/ords->ring 6.95,2.05, 2.98,2.06, 3.92,-0.08, 6.95,2.05)
        hole2 (umm-s/ords->ring 5.18,6.92, -1.79,7.01, -2.65,5, 4.29,5.05, 5.18,6.92)
        polygon-with-holes  (make-coll :geodetic "polygon-with-holes" (poly/polygon [outer hole1 hole2]))

        ;; Cartesian Polygons
        wide-north-cart (make-coll :cartesian "wide-north-cart" (polygon -70 20, 70 20, 70 30, -70 30, -70 20))
        wide-south-cart (make-coll :cartesian "wide-south-cart" (polygon -70 -30, 70 -30, 70 -20, -70 -20, -70 -30))
        very-wide-cart (make-coll :cartesian "very-wide-cart" (polygon -180 40, -180 35, 180 35, 180 40, -180 40))
        very-tall-cart (make-coll :cartesian "very-tall-cart" (polygon -160 90, -160 -90, -150 -90, -150 90, -160 90))

        outer-cart (umm-s/ords->ring -5.26 -22.59 11.56 -22.77 10.47 -11.29 -5.86 -11.37 -5.26 -22.59)
        hole1-cart (umm-s/ords->ring 6.95 -17.95 2.98 -17.94 3.92 -20.08 6.95 -17.95)
        hole2-cart (umm-s/ords->ring 5.18 -13.08 -1.79 -12.99 -2.65 -15 4.29 -14.95 5.18 -13.08)
        polygon-with-holes-cart (make-coll :cartesian "polygon-with-holes-cart" (poly/polygon [outer-cart hole1-cart hole2-cart]))

        ;; Points
        washington-dc (make-coll :geodetic "washington-dc" (p/point -77 38.9))
        richmond (make-coll :geodetic "richmond" (p/point -77.4 37.54))
        north-pole (make-coll :geodetic "north-pole" (p/point 0 90))
        south-pole (make-coll :geodetic "south-pole" (p/point 0 -90))
        normal-point (make-coll :geodetic "normal-point" (p/point 10 22))
        am-point (make-coll :geodetic "am-point" (p/point 180 22))
        esri-point (make-coll :geodetic "esri-point" (p/point -80 35))]
    (index/wait-until-indexed)

    (testing "Search by GeoJSON shapefile"
      (are3 [shapefile items]
        (let [found (search/find-refs-with-multi-part-form-post
                     :collection
                     [{:name "shapefile"
                       :content (io/file (io/resource (str "shapefiles/" shapefile)))
                       :mime-type "application/geo+json"}
                      {:name "provider"
                       :content "PROV1"}])]
          (d/assert-refs-match items found))

        "Single Polygon box around VA and DC"
        "box.geojson" [whole-world very-wide-cart washington-dc richmond]

        "Single Polygon box over North pole"
        "north_pole_poly.geojson" [north-pole touches-np on-np whole-world very-tall-cart along-am-line]

        "Single Polygon over Antartica"
        "antartica.geojson" [south-pole touches-sp on-sp whole-world wide-south very-tall-cart]

        "Single Polygon over Southern Africa"
        "southern_africa.geojson" [whole-world polygon-with-holes polygon-with-holes-cart normal-line normal-line-cart normal-brs wide-south-cart]

        "Single Polygon around Virgina with hole around DC"
        "polygon_with_hole.geojson" [whole-world very-wide-cart richmond]

        "Single feature, multiple polygons around DC and Richnmond"
        "multi-poly.geojson" [whole-world very-wide-cart washington-dc richmond]

        "Multiple feature, single Polygons around DC and Richnmond"
        "multi-feature.geojson" [whole-world very-wide-cart washington-dc richmond]
        
        "Polygon across the antimeridian"
        "antimeridian.geojson" [whole-world across-am-poly across-am-br am-point very-tall-cart very-wide-cart along-am-line]

        "Line near North pole"
        "np_line.geojson" [on-np whole-world]

        "Line from DC to Richmond"
        "dc_richmond_line.geojson" [whole-world very-wide-cart washington-dc richmond]

        "Single Point Washington DC"
        "single_point_dc.geojson" [whole-world very-wide-cart washington-dc]))))
