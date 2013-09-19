package liquibase.snapshot;

import liquibase.CatalogAndSchema;
import liquibase.database.Database;
import liquibase.exception.DatabaseException;
import liquibase.exception.UnexpectedLiquibaseException;
import liquibase.servicelocator.ServiceLocator;
import liquibase.structure.DatabaseObject;
import liquibase.structure.core.*;
import liquibase.diff.compare.DatabaseObjectComparatorFactory;

import java.lang.reflect.Field;
import java.util.*;

public abstract class DatabaseSnapshot {

    private List<DatabaseObject> originalExamples;
    private SnapshotControl snapshotControl;
    private Database database;
    private Map<Class<? extends DatabaseObject>, Map<String, Set<DatabaseObject>>> allFoundByHash = new HashMap<Class<? extends DatabaseObject>, Map<String, Set<DatabaseObject>>>();
    private Map<Class<? extends DatabaseObject>, Set<DatabaseObject>> knownNull = new HashMap<Class<? extends DatabaseObject>, Set<DatabaseObject>>();

    private Map<String, ResultSetCache> resultSetCaches = new HashMap<String, ResultSetCache>();

    DatabaseSnapshot(DatabaseObject[] examples, Database database, SnapshotControl snapshotControl) throws DatabaseException, InvalidExampleException {
        this.originalExamples = Arrays.asList(examples);
        this.database = database;
        this.snapshotControl = snapshotControl;

        for (DatabaseObject obj : examples) {
            include(obj);
        }
    }

    public DatabaseSnapshot(DatabaseObject[] examples, Database database) throws DatabaseException, InvalidExampleException {
        this(examples, database, new SnapshotControl(database));
    }

    public SnapshotControl getSnapshotControl() {
        return snapshotControl;
    }

    public Database getDatabase() {
        return database;
    }

    public ResultSetCache getResultSetCache(String key) {
        if (!resultSetCaches.containsKey(key)) {
            resultSetCaches.put(key, new ResultSetCache());
        }
        return resultSetCaches.get(key);
    }

    /**
     * Include the object described by the passed example object in this snapshot. Returns the object snapshot or null if the object does not exist in the database.
     * If the same object was returned by an earlier include() call, the same object instance will be returned.
     */
    protected  <T extends DatabaseObject> T include(T example) throws DatabaseException, InvalidExampleException {
        if (example == null) {
            return null;
        }

        if (database.isSystemObject(example)) {
            return null;
        }

        if (example instanceof Schema && example.getName() == null && (((Schema) example).getCatalog() == null || ((Schema) example).getCatalogName() == null)) {
            CatalogAndSchema catalogAndSchema = database.correctSchema(((Schema) example).toCatalogAndSchema());
            example = (T) new Schema(catalogAndSchema.getCatalogName(), catalogAndSchema.getSchemaName());
        }

        if (!snapshotControl.shouldInclude(example.getClass())) {
            return example;
        }

       T existing = get(example);
        if (existing != null) {
            return existing;
        }
        if (isKnownNull(example)) {
            return null;
        }

        SnapshotGeneratorChain chain = createGeneratorChain(example.getClass(), database);
        T object = chain.snapshot(example, this);

        if (object == null) {
            Set<DatabaseObject> collection = knownNull.get(example.getClass());
            if (collection == null) {
                collection = new HashSet<DatabaseObject>();
                knownNull.put(example.getClass(), collection);
            }
            collection.add(example);

        } else {
            Map<String, Set<DatabaseObject>> collectionMap = allFoundByHash.get(object.getClass());
            if (collectionMap == null) {
                collectionMap = new HashMap<String, Set<DatabaseObject>>();
                allFoundByHash.put(object.getClass(), collectionMap);
            }

            String hash = DatabaseObjectComparatorFactory.getInstance().hash(object, database);
            Set<DatabaseObject> collection = collectionMap.get(hash);
            if (collection == null) {
                collection = new HashSet<DatabaseObject>();
                collectionMap.put(hash, collection);
            }
            collection.add(object);

            try {
                includeNestedObjects(object);
            } catch (InstantiationException e) {
                throw new UnexpectedLiquibaseException(e);
            } catch (IllegalAccessException e) {
                throw new UnexpectedLiquibaseException(e);
            }
        }
        return object;
    }

    private void includeNestedObjects(DatabaseObject object) throws DatabaseException, InvalidExampleException, InstantiationException, IllegalAccessException {
            for (String field : new HashSet<String>(object.getAttributes())) {
                Object fieldValue = object.getAttribute(field, Object.class);
                Object newFieldValue = replaceObject(fieldValue);
                if (fieldValue != newFieldValue) {
                    object.setAttribute(field, newFieldValue);
                }

            }
    }

    private Object replaceObject(Object fieldValue) throws DatabaseException, InvalidExampleException, IllegalAccessException, InstantiationException {
        if (fieldValue == null) {
            return null;
        }
        if (fieldValue instanceof DatabaseObject) {
            if (!snapshotControl.shouldInclude(((DatabaseObject) fieldValue).getClass())) {
                return fieldValue;
            }
            if (((DatabaseObject) fieldValue).getSnapshotId() == null) {
                return include((DatabaseObject) fieldValue);
            } else {
                return fieldValue;
            }
            //            } else if (Set.class.isAssignableFrom(field.getType())) {
            //                field.setAccessible(true);
            //                Set fieldValue = field.get(object);
            //                for (Object val : fieldValue) {
            //
            //                }
        } else if (fieldValue instanceof Collection) {
            Iterator fieldValueIterator = ((Collection) fieldValue).iterator();
            List newValues = new ArrayList();
            while (fieldValueIterator.hasNext()) {
                Object obj = fieldValueIterator.next();
                if (fieldValue instanceof DatabaseObject && !snapshotControl.shouldInclude(((DatabaseObject) fieldValue).getClass())) {
                    return fieldValue;
                }

                if (obj instanceof DatabaseObject && ((DatabaseObject) obj).getSnapshotId() == null) {
                    obj = include((DatabaseObject) obj);
                }
                if (obj != null) {
                    newValues.add(obj);
                }
            }
            Collection newCollection = (Collection) fieldValue.getClass().newInstance();
            newCollection.addAll(newValues);
            return newCollection;
        } else if (fieldValue instanceof Map) {
            Map newMap = (Map) fieldValue.getClass().newInstance();
            for (Map.Entry entry : (Set<Map.Entry>) ((Map) fieldValue).entrySet()) {
                Object key = replaceObject(entry.getKey());
                Object value = replaceObject(entry.getValue());

                if (key != null) {
                    newMap.put(key, value);
                }
            }

            return newMap;

        }

        return fieldValue;
    }

    /**
     * Returns the object described by the passed example if it is already included in this snapshot.
     */
    public <DatabaseObjectType extends DatabaseObject> DatabaseObjectType get(DatabaseObjectType example) {
        Map<String, Set<DatabaseObject>> databaseObjectsByHash = allFoundByHash.get(example.getClass());

        if (databaseObjectsByHash == null) {
            return null;
        }

        String hash = DatabaseObjectComparatorFactory.getInstance().hash(example, database);

        Set<DatabaseObject> databaseObjects = databaseObjectsByHash.get(hash);
        if (databaseObjects == null) {
            return null;
        }
        for (DatabaseObject obj : databaseObjects) {
            if (DatabaseObjectComparatorFactory.getInstance().isSameObject(obj, example, database)) {
                //noinspection unchecked
                return (DatabaseObjectType) obj;
            }
        }
        return null;
    }

    /**
     * Returns all objects of the given type that are already included in this snapshot.
     */
    public <DatabaseObjectType extends  DatabaseObject> Set<DatabaseObjectType> get(Class<DatabaseObjectType> type) {

        Set<DatabaseObject> returnSet = new HashSet<DatabaseObject>();

        Map<String, Set<DatabaseObject>> allFound = allFoundByHash.get(type);
        if (allFound != null) {
            for (Set<DatabaseObject> objects : allFound.values()) {
                returnSet.addAll(objects);
            }
        }

        return (Set<DatabaseObjectType>) Collections.unmodifiableSet(returnSet);
    }


    private SnapshotGeneratorChain createGeneratorChain(Class<? extends DatabaseObject> databaseObjectType, Database database) {
        SortedSet<SnapshotGenerator> generators = SnapshotGeneratorFactory.getInstance().getGenerators(databaseObjectType, database);
        if (generators == null || generators.size() == 0) {
            return null;
        }
        //noinspection unchecked
        return new SnapshotGeneratorChain(generators);
    }

    private boolean isKnownNull(DatabaseObject example) {
        Set<DatabaseObject> databaseObjects = knownNull.get(example.getClass());
        if (databaseObjects == null) {
            return false;
        }
        for (DatabaseObject obj : databaseObjects) {
            if (DatabaseObjectComparatorFactory.getInstance().isSameObject(obj, example, database)) {
                return true;
            }
        }
        return false;
    }
}
