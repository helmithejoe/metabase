(ns metabase.sync.field-values
  "Logic for updating cached FieldValues for fields in a database."
  (:require [clojure.tools.logging :as log]
            [metabase.models
             [field :refer [Field]]
             [field-values :refer [FieldValues] :as field-values]]
            [metabase.sync
             [interface :as i]
             [util :as sync-util]]
            [metabase.util :as u]
            [puppetlabs.i18n.core :refer [trs]]
            [schema.core :as s]
            [toucan.db :as db]))

(s/defn ^:private clear-field-values-for-field! [field :- i/FieldInstance]
  (when (db/exists? FieldValues :field_id (u/get-id field))
    (log/debug (format "Based on cardinality and/or type information, %s should no longer have field values.\n"
                       (sync-util/name-for-logging field))
               "Deleting FieldValues...")
    (db/delete! FieldValues :field_id (u/get-id field))
    ::field-values/fv-deleted))

(s/defn ^:private update-field-values-for-field! [field :- i/FieldInstance]
  (log/debug (u/format-color 'green "Looking into updating FieldValues for %s" (sync-util/name-for-logging field)))
  (field-values/create-or-update-field-values! field))

(s/defn update-field-values-for-table!
  "Update the cached FieldValues for all Fields (as needed) for TABLE."
  [table :- i/TableInstance]
  (reduce (fn [fv-change-counts field]
            (let [result (sync-util/with-error-handling (format "Error updating field values for %s" (sync-util/name-for-logging field))
                           (if (field-values/field-should-have-field-values? field)
                             (update-field-values-for-field! field)
                             (clear-field-values-for-field! field)))]

              (if (instance? Exception result)
                (update fv-change-counts :errors inc)
                (case result
                  ::field-values/fv-created
                  (update fv-change-counts :created inc)
                  ::field-values/fv-updated
                  (update fv-change-counts :updated inc)
                  ::field-values/fv-deleted
                  (update fv-change-counts :deleted inc)
                  fv-change-counts))))
          {:errors 0, :created 0, :updated 0, :deleted 0}
          (db/select Field :table_id (u/get-id table), :active true, :visibility_type "normal")))

(s/defn ^:private update-field-values-for-database!
  [database :- i/DatabaseInstance]
  (apply merge-with + (map update-field-values-for-table! (sync-util/db->sync-tables database))))

(defn- update-field-values-summary [{:keys [created updated deleted errors]}]
  (trs "Updated {0} field value sets, created {1}, deleted {2} with {3} errors"
       updated created deleted errors))

(def ^:private field-values-steps
  [(sync-util/create-sync-step "update-field-values" update-field-values-for-database! update-field-values-summary)])

(s/defn update-field-values!
  "Update the cached FieldValues (distinct values for categories and certain other fields that are shown
   in widgets like filters) for the Tables in DATABASE (as needed)."
  [database :- i/DatabaseInstance]
  (sync-util/sync-operation :cache-field-values database (format "Cache field values in %s"
                                                                 (sync-util/name-for-logging database))
    (sync-util/run-sync-operation "field values scanning" database field-values-steps)))
