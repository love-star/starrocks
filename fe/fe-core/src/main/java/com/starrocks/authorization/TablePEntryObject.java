// Copyright 2021-present StarRocks, Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.starrocks.authorization;

import com.google.common.base.Preconditions;
import com.google.gson.annotations.SerializedName;
import com.starrocks.catalog.Catalog;
import com.starrocks.catalog.Database;
import com.starrocks.catalog.ExternalCatalog;
import com.starrocks.catalog.InternalCatalog;
import com.starrocks.catalog.Table;
import com.starrocks.connector.exception.StarRocksConnectorException;
import com.starrocks.qe.GlobalVariable;
import com.starrocks.server.CatalogMgr;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.sql.common.MetaNotFoundException;

import java.util.List;
import java.util.Objects;

public class TablePEntryObject implements PEntryObject {
    @SerializedName(value = "ci")
    private long catalogId;
    @SerializedName(value = "d")
    protected String databaseUUID;
    @SerializedName(value = "t")
    protected String tableUUID;

    protected TablePEntryObject(long catalogId, String databaseUUID, String tableUUID) {
        this.catalogId = catalogId;
        this.tableUUID = tableUUID;
        this.databaseUUID = databaseUUID;
    }

    protected TablePEntryObject(String databaseUUID, String tableUUID) {
        this.catalogId = InternalCatalog.DEFAULT_INTERNAL_CATALOG_ID;
        this.tableUUID = tableUUID;
        this.databaseUUID = databaseUUID;
    }

    public String getDatabaseUUID() {
        return databaseUUID;
    }

    public String getTableUUID() {
        return tableUUID;
    }

    public long getCatalogId() {
        return catalogId;
    }

    public static TablePEntryObject generate(List<String> tokens) throws PrivilegeException {
        String catalogName = null;
        long catalogId;
        if (tokens.size() == 3) {
            // This is true only when we are initializing built-in roles like root and db_admin
            if (tokens.get(0).equals("*")) {
                return new TablePEntryObject(PrivilegeBuiltinConstants.ALL_CATALOGS_ID,
                        PrivilegeBuiltinConstants.ALL_DATABASES_UUID, PrivilegeBuiltinConstants.ALL_TABLES_UUID);
            }
            catalogName = tokens.get(0);

            // It's very trick here.
            // Because of the historical legacy code, create external table belongs to internal catalog from the grammatical level
            // . In terms of code implementation, the catalog obtained from TableName turned out to be external catalog!
            // As a result, the authorization grant stmt is authorized according to the internal catalog,
            // but the external catalog is used for authentication.
            if (CatalogMgr.ResourceMappingCatalog.isResourceMappingCatalog(catalogName)) {
                catalogName = InternalCatalog.DEFAULT_INTERNAL_CATALOG_NAME;
            }

            tokens = tokens.subList(1, tokens.size());
        } else if (tokens.size() != 2) {
            throw new PrivilegeException(
                    "invalid object tokens, should have two or three elements, current: " + tokens);
        }

        // Default to internal_catalog when no catalog explicitly selected.
        if (catalogName == null || CatalogMgr.isInternalCatalog(catalogName)) {
            catalogName = InternalCatalog.DEFAULT_INTERNAL_CATALOG_NAME;
            catalogId = InternalCatalog.DEFAULT_INTERNAL_CATALOG_ID;
        } else {
            Catalog catalog = GlobalStateMgr.getCurrentState().getCatalogMgr().getCatalogByName(catalogName);
            if (catalog == null) {
                throw new PrivObjNotFoundException("cannot find catalog: " + catalogName);
            }
            catalogId = catalog.getId();
        }

        if (Objects.equals(tokens.get(0), "*")) {
            return new TablePEntryObject(
                    catalogId,
                    PrivilegeBuiltinConstants.ALL_DATABASES_UUID,
                    PrivilegeBuiltinConstants.ALL_TABLES_UUID);
        }

        String dbUUID = DbPEntryObject.getDatabaseUUID(catalogName, tokens.get(0));
        String tableUUID = getTableUUID(catalogName, tokens.get(0), tokens.get(1));
        return new TablePEntryObject(catalogId, dbUUID, tableUUID);
    }

    /**
     * for internal table, use {@link Table#getUUID()} as privilege id.
     * for external table, use table name as privilege id.
     */
    private static String getTableUUID(String catalogName, String dbToken, String tableToken)
            throws PrivObjNotFoundException {
        Preconditions.checkArgument(!dbToken.equals("*"));
        if (tableToken.equals("*")) {
            return PrivilegeBuiltinConstants.ALL_TABLES_UUID;
        }

        if (CatalogMgr.isInternalCatalog(catalogName)) {
            Table table;
            try {
                table = GlobalStateMgr.getCurrentState().getLocalMetastore().getTable(dbToken, tableToken);
            } catch (StarRocksConnectorException e) {
                throw new PrivObjNotFoundException("cannot find table " +
                        tableToken + " in db " + dbToken + ", msg: " + e.getMessage());
            }
            if (table == null || table.isOlapView() || table.isMaterializedView()) {
                throw new PrivObjNotFoundException("cannot find table " +
                        tableToken + " in db " + dbToken);
            }
            return table.getUUID();
        }

        // for table in external catalog, return tableName directly without validation
        return tableToken;
    }

    /**
     * if the current table matches other table, including fuzzy matching.
     * <p>
     * this(db1.tbl1), other(db1.tbl1) -> true
     * this(db1.tbl1), other(db1.ALL) -> true
     * this(db1.ALL), other(db1.tbl1) -> false
     * - For external catalogs, match database and table names case-insensitively.
     * - For internal catalog, keep case-sensitive match.
     */
    @Override
    public boolean match(Object obj) {
        if (!(obj instanceof TablePEntryObject)) {
            return false;
        }
        TablePEntryObject other = (TablePEntryObject) obj;
        if (other.catalogId == PrivilegeBuiltinConstants.ALL_CATALOGS_ID) {
            return true;
        }
        if (Objects.equals(other.databaseUUID, PrivilegeBuiltinConstants.ALL_DATABASES_UUID)) {
            return this.catalogId == other.catalogId;
        }
        if (Objects.equals(other.tableUUID, PrivilegeBuiltinConstants.ALL_TABLES_UUID)) {
            return this.catalogId == other.catalogId &&
                    matchDatabaseUUID(other);
        }
        return this.catalogId == other.catalogId &&
                matchDatabaseUUID(other) &&
                matchTableUUID(other);
    }

    private boolean matchDatabaseUUID(TablePEntryObject other) {
        if (isExternalCatalog(this.catalogId)) {
            return Catalog.getCompatibleDbUUID(this.databaseUUID)
                    .equalsIgnoreCase(Catalog.getCompatibleDbUUID(other.databaseUUID));
        }
        return Catalog.getCompatibleDbUUID(this.databaseUUID)
                .equals(Catalog.getCompatibleDbUUID(other.databaseUUID));
    }

    private boolean matchTableUUID(TablePEntryObject other) {
        if (isExternalCatalog(this.catalogId) || GlobalVariable.enableTableNameCaseInsensitive) {
            return Catalog.getCompatibleTableUUID(this.tableUUID)
                    .equalsIgnoreCase(Catalog.getCompatibleTableUUID(other.tableUUID));
        }
        return Catalog.getCompatibleTableUUID(this.tableUUID)
                .equals(Catalog.getCompatibleTableUUID(other.tableUUID));
    }

    private boolean isExternalCatalog(long catalogId) {
        return catalogId != InternalCatalog.DEFAULT_INTERNAL_CATALOG_ID;
    }

    @Override
    public boolean isFuzzyMatching() {
        return catalogId == PrivilegeBuiltinConstants.ALL_CATALOGS_ID ||
                Objects.equals(databaseUUID, PrivilegeBuiltinConstants.ALL_DATABASES_UUID) ||
                Objects.equals(tableUUID, PrivilegeBuiltinConstants.ALL_TABLES_UUID);
    }

    @Override
    public boolean validate() {
        if (catalogId == InternalCatalog.DEFAULT_INTERNAL_CATALOG_ID) {
            Database db = GlobalStateMgr.getCurrentState().getLocalMetastore()
                    .getDbIncludeRecycleBin(Long.parseLong(this.databaseUUID));
            if (db == null) {
                return false;
            }
            return GlobalStateMgr.getCurrentState().getLocalMetastore()
                    .getTableIncludeRecycleBin(db, Long.parseLong(this.tableUUID)) != null;
        }
        // do not validate privilege of external table
        return true;
    }

    @Override
    public int compareTo(PEntryObject obj) {
        if (!(obj instanceof TablePEntryObject)) {
            throw new ClassCastException("cannot cast " + obj.getClass().toString() + " to " + this.getClass());
        }

        TablePEntryObject o = (TablePEntryObject) obj;
        if (this.catalogId == o.catalogId) {
            // Always put the fuzzy matching object at the front of the privilege entry list
            // when sorting in ascendant order.
            if (Objects.equals(this.databaseUUID, o.databaseUUID)) {
                if (Objects.equals(this.tableUUID, o.tableUUID)) {
                    return 0;
                } else if (Objects.equals(this.tableUUID, PrivilegeBuiltinConstants.ALL_TABLES_UUID)) {
                    return -1;
                } else if (Objects.equals(o.tableUUID, PrivilegeBuiltinConstants.ALL_TABLES_UUID)) {
                    return 1;
                } else {
                    return this.tableUUID.compareTo(o.tableUUID);
                }
            } else if (Objects.equals(this.databaseUUID, PrivilegeBuiltinConstants.ALL_DATABASES_UUID)) {
                return -1;
            } else if (Objects.equals(o.databaseUUID, PrivilegeBuiltinConstants.ALL_DATABASES_UUID)) {
                return 1;
            } else {
                return this.databaseUUID.compareTo(o.databaseUUID);
            }
        } else if (this.catalogId == PrivilegeBuiltinConstants.ALL_CATALOGS_ID) {
            return -1;
        } else if (o.catalogId == PrivilegeBuiltinConstants.ALL_CATALOGS_ID) {
            return 1;
        } else {
            return (int) (this.catalogId - o.catalogId);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        TablePEntryObject that = (TablePEntryObject) o;
        return this.catalogId == that.catalogId &&
                Objects.equals(Catalog.getCompatibleDbUUID(this.databaseUUID), Catalog.getCompatibleDbUUID(that.databaseUUID)) &&
                Objects.equals(Catalog.getCompatibleTableUUID(this.tableUUID), Catalog.getCompatibleTableUUID(that.tableUUID));
    }

    @Override
    public int hashCode() {
        return Objects.hash(catalogId, Catalog.getCompatibleDbUUID(databaseUUID), Catalog.getCompatibleTableUUID(tableUUID));
    }

    @Override
    public PEntryObject clone() {
        return new TablePEntryObject(catalogId, databaseUUID, tableUUID);
    }

    protected String toStringImpl(String plural) {
        StringBuilder sb = new StringBuilder();

        if (Objects.equals(getDatabaseUUID(), PrivilegeBuiltinConstants.ALL_DATABASES_UUID)) {
            sb.append("ALL ").append(plural).append(" IN ALL DATABASES");
        } else {
            String dbName;
            Database database = null;
            if (CatalogMgr.isInternalCatalog(catalogId)) {
                database = GlobalStateMgr.getCurrentState().getMetadataMgr()
                        .getDb(Long.parseLong(getDatabaseUUID()));
                if (database == null) {
                    throw new MetaNotFoundException("Cannot find database : " + databaseUUID);
                }
                dbName = database.getFullName();
            } else {
                dbName = ExternalCatalog.getDbNameFromUUID(databaseUUID);
            }

            if (Objects.equals(getTableUUID(), PrivilegeBuiltinConstants.ALL_TABLES_UUID)) {
                sb.append("ALL ").append(plural).append(" IN DATABASE ").append(dbName);
            } else {
                String tblName = null;
                if (CatalogMgr.isInternalCatalog(catalogId)) {
                    Table table = GlobalStateMgr.getCurrentState().getLocalMetastore()
                            .getTable(Long.parseLong(getDatabaseUUID()), Long.parseLong(getTableUUID()));
                    if (table == null) {
                        throw new MetaNotFoundException("Cannot find table : " + tableUUID);
                    }
                    tblName = table.getName();
                } else {
                    tblName = ExternalCatalog.getTableNameFromUUID(tableUUID);
                }
                sb.append(dbName).append(".").append(tblName);
            }
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return toStringImpl("TABLES");
    }
}
