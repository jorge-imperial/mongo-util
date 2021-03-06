package com.mongodb.shardsync;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.in;
import static com.mongodb.client.model.Filters.ne;
import static com.mongodb.client.model.Filters.regex;
import static com.mongodb.client.model.Projections.include;
import static org.bson.codecs.configuration.CodecRegistries.fromProviders;
import static org.bson.codecs.configuration.CodecRegistries.fromRegistries;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;
import org.bson.BsonDocument;
import org.bson.BsonTimestamp;
import org.bson.Document;
import org.bson.RawBsonDocument;
import org.bson.codecs.DocumentCodec;
import org.bson.codecs.configuration.CodecRegistry;
import org.bson.codecs.pojo.PojoCodecProvider;
import org.bson.conversions.Bson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.MongoCommandException;
import com.mongodb.MongoTimeoutException;
import com.mongodb.ServerAddress;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.MongoIterable;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.result.DeleteResult;
import com.mongodb.internal.dns.DefaultDnsResolver;
import com.mongodb.model.IndexSpec;
import com.mongodb.model.Mongos;
import com.mongodb.model.Namespace;
import com.mongodb.model.Shard;
import com.mongodb.model.ShardTimestamp;

/**
 * This class encapsulates the client related objects needed for each source and
 * destination
 *
 */
public class ShardClient {

    private static Logger logger = LoggerFactory.getLogger(ShardClient.class);
    
    private DocumentCodec codec = new DocumentCodec();

    private static final String MONGODB_SRV_PREFIX = "mongodb+srv://";

    private final static List<Document> countPipeline = new ArrayList<Document>();
    static {
        countPipeline.add(Document.parse("{ $group: { _id: null, count: { $sum: 1 } } }"));
        countPipeline.add(Document.parse("{ $project: { _id: 0, count: 1 } }"));
    }

    private String name;
    private String version;
    private List<Integer> versionArray;
    //private MongoClientURI mongoClientURI;
    private MongoClient mongoClient;
    private MongoDatabase configDb;
    private Map<String, Shard> shardsMap = new LinkedHashMap<String, Shard>();
    
    private Map<String, Shard> tertiaryShardsMap = new LinkedHashMap<String, Shard>();
    
    private ConnectionString connectionString;
    private MongoClientSettings mongoClientSettings;
    
    private ConnectionString csrsConnectionString;
    private MongoClientSettings csrsMongoClientSettings;
    private MongoClient csrsMongoClient;
    
    //private String username;
    //private String password;
    //private MongoClientOptions mongoClientOptions;

    private List<Mongos> mongosList = new ArrayList<Mongos>();
    private Map<String, MongoClient> mongosMongoClients = new TreeMap<String, MongoClient>();

    private Map<String, Document> collectionsMap = new TreeMap<String, Document>();

    private Map<String, MongoClient> shardMongoClients = new TreeMap<String, MongoClient>();
    
    private List<String> srvHosts;
    
    private Collection<String> shardIdFilter;
    
    private boolean patternedUri;
    private boolean mongos;
    private String connectionStringPattern;
    private String rsPattern;
    private String csrsUri;
    
    public ShardClient(String name, String clusterUri, Collection<String> shardIdFilter) {
    	
    	this.patternedUri = clusterUri.contains("%s");
    	if (patternedUri) {
    		this.connectionStringPattern = clusterUri;
    		// example mongodb://admin@cluster1-%s-%s-%s.wxyz.mongodb.net:27017/?ssl=true&authSource=admin
    		String csrsUri = String.format(clusterUri, "config", 0, 0);
    		logger.debug(name + " csrsUri from pattern: " + csrsUri);
    		this.connectionString = new ConnectionString(csrsUri);
    	} else {
    		this.connectionString = new ConnectionString(clusterUri);
    	}
    	
    	if (connectionString.isSrvProtocol()) {
    		throw new IllegalArgumentException("srv protocol not supported, please configure a single mongos mongodb:// connection string");
    	}
    	
    	this.name = name;
    	this.shardIdFilter = shardIdFilter;
        logger.debug(String.format("%s client, uri: %s", name, clusterUri));
    }
    
    public ShardClient(String name, String clusterUri) {
    	this(name, clusterUri, null);
    }
    
    public void init() {
    	if (csrsUri != null) {
    		logger.debug(name + " csrsUri: " + csrsUri);
    		this.csrsConnectionString = new ConnectionString(csrsUri);
    		this.csrsMongoClientSettings = MongoClientSettings.builder()
                    .applyConnectionString(csrsConnectionString)
                    .build();
    		this.csrsMongoClient = MongoClients.create(csrsMongoClientSettings);
    	}
    	mongoClientSettings = MongoClientSettings.builder()
                .applyConnectionString(connectionString)
                .build();
         
        mongoClient = MongoClients.create(mongoClientSettings);
        
        CodecRegistry pojoCodecRegistry = fromRegistries(MongoClientSettings.getDefaultCodecRegistry(),
                fromProviders(PojoCodecProvider.builder().automatic(true).build()));

        
        try {
        	Document dbgridResult = adminCommand(new Document("isdbgrid", 1));
        	Integer dbgrid = dbgridResult.getInteger("isdbgrid");
        	mongos = dbgrid.equals(1);
        } catch (MongoCommandException mce) {
        	
        }
        
        
        configDb = mongoClient.getDatabase("config").withCodecRegistry(pojoCodecRegistry);
    	
        populateShardList();

        Document destBuildInfo = adminCommand(new Document("buildinfo", 1));
        version = destBuildInfo.getString("version");
        versionArray = (List<Integer>) destBuildInfo.get("versionArray");
        logger.debug(String.format("%s : MongoDB version: %s, mongos: %s", name, version, mongos));

        populateMongosList();
    }

    /**
     * If we're using patternedUri, we assume that the source or dest is an Atlas cluster
     * that's in the midst of LiveMigrate and this tool is being used to reverse sync.
     * In that case the data that's in the config server is WRONG. But, we'll use that to assume the number of shards
     * (for now)
     * 
     */
    private void populateShardList() {

    	MongoCollection<Shard> shardsColl = configDb.getCollection("shards", Shard.class);
        FindIterable<Shard> shards = shardsColl.find().sort(Sorts.ascending("_id"));
        for (Shard sh : shards) {
        	
        	// TODO fix this for patterned uri
//        	if (shardIdFilter != null && ! shardIdFilter.contains(sh.getId())) {
//        		continue;
//        	}
        	
        	if (!patternedUri) {
        		logger.debug(String.format("%s: populateShardList shard: %s", name, sh.getHost()));
        	}
        	String rsName = StringUtils.substringBefore(sh.getHost(), "/");
        	sh.setRsName(rsName);
            shardsMap.put(sh.getId(), sh);
        }
        
    	if (patternedUri) {
    		int shardCount = shardsMap.size();
    		tertiaryShardsMap.putAll(shardsMap);
    		shardsMap.clear();
    		for (int shardNum = 0; shardNum < shardCount; shardNum++) {
    			
    			String hostBasePre = StringUtils.substringAfter(connectionStringPattern, "mongodb://");
    			String hostBase = StringUtils.substringBefore(hostBasePre, "/");
    			if (hostBase.contains("@")) {
    				hostBase = StringUtils.substringAfter(hostBase, "@");
    			}
    			String host0 = String.format(hostBase, "shard", shardNum, 0);
    			String host1 = String.format(hostBase, "shard", shardNum, 1);
    			String rsName = String.format(this.rsPattern, "shard", shardNum);
    			Shard sh = new Shard();
    			sh.setId(rsName);
    			sh.setRsName(rsName);
    			sh.setHost(String.format("%s/%s,%s", rsName, host0, host1));
    			shardsMap.put(sh.getId(), sh);
    			logger.debug(String.format("%s: populateShardList formatted shard name: %s", name, sh.getHost()));
    		}
    		
    	}
        
        logger.debug(name + ": populateShardList complete, " + shardsMap.size() + " shards added");
    }

    private void populateMongosList() {
    	
    	if (patternedUri) {
    		logger.debug("populateMongosList() skipping, patternedUri");
    		return;
    	}
        
        if (connectionString.isSrvProtocol()) {
            
            DefaultDnsResolver resolver = new DefaultDnsResolver();
            srvHosts = resolver.resolveHostFromSrvRecords(connectionString.getHosts().get(0));
            
            for (String hostPort : srvHosts) {
                logger.debug("populateMongosList() mongos srvHost: " + hostPort);
                
                String host = StringUtils.substringBefore(hostPort, ":");
                Integer port = Integer.parseInt(StringUtils.substringAfter(hostPort, ":"));
                
                MongoClientSettings.Builder settingsBuilder = MongoClientSettings.builder();
                settingsBuilder.applyToClusterSettings(builder ->
                        builder.hosts(Arrays.asList(new ServerAddress(host, port))));
                if (connectionString.getSslEnabled() != null) {
                    settingsBuilder.applyToSslSettings(builder -> builder.enabled(connectionString.getSslEnabled()));
                }
                if (connectionString.getCredential() != null) {
                    settingsBuilder.credential(connectionString.getCredential());
                }
                MongoClientSettings settings = settingsBuilder.build();

                MongoClient mongoClient = MongoClients.create(settings);
                mongosMongoClients.put(hostPort, mongoClient);
            }
            
        } else {
            MongoCollection<Mongos> mongosColl = configDb.getCollection("mongos", Mongos.class);
            // LocalDateTime oneHourAgo = LocalDateTime.now().minusHours(1);

            // TODO this needs to take into account "dead" mongos instances
            int limit = 9999;
            if (name.equals("source")) {
                limit = 5;
            }
            mongosColl.find().sort(Sorts.ascending("ping")).limit(limit).into(mongosList);
            for (Mongos mongos : mongosList) {
                
                //logger.debug(name + " mongos: " + mongos.getId());
                
                String hostPort = mongos.getId();
                String host = StringUtils.substringBefore(hostPort, ":");
                Integer port = Integer.parseInt(StringUtils.substringAfter(hostPort, ":"));
                
                MongoClientSettings.Builder settingsBuilder = MongoClientSettings.builder();
                settingsBuilder.applyToClusterSettings(builder ->
                        builder.hosts(Arrays.asList(new ServerAddress(host, port))));
                if (connectionString.getSslEnabled() != null) {
                    settingsBuilder.applyToSslSettings(builder -> builder.enabled(connectionString.getSslEnabled()));
                }
                if (connectionString.getCredential() != null) {
                    settingsBuilder.credential(connectionString.getCredential());
                }
                MongoClientSettings settings = settingsBuilder.build();
                
                MongoClient mongoClient = MongoClients.create(settings);
                mongosMongoClients.put(mongos.getId(), mongoClient);
            }
               
        }
        

        
        logger.debug(name + " populateMongosList complete, " + mongosMongoClients.size() + " mongosMongoClients added");
    }
    
    /**
     *  Populate only a subset of all collections. Useful if there are a very large number of
     *  namespaces present.
     */
    public void populateCollectionsMap(Set<String> namespaces) {
    	
    	if (! collectionsMap.isEmpty()) {
    		return;
    	}
    	logger.debug("Starting populateCollectionsMap()");
        MongoCollection<Document> shardsColl = configDb.getCollection("collections");
        Bson filter = null;
        if (namespaces == null || namespaces.isEmpty()) {
            filter = eq("dropped", false);
        } else {
            filter = and(eq("dropped", false), in("_id", namespaces));
        }
        FindIterable<Document> colls = shardsColl.find(filter).sort(Sorts.ascending("_id"));
        for (Document c : colls) {
            String id = (String)c.get("_id");
            collectionsMap.put(id, c);
        }
        logger.debug(String.format("%s Finished populateCollectionsMap(), %s collections loaded from config server", name, collectionsMap.size()));
    }

    public void populateCollectionsMap() {
        populateCollectionsMap(null);
    }

    public void populateShardMongoClients() {
        // MongoCredential sourceCredentials = mongoClientURI.getCredentials();
    	
    	if (shardMongoClients.size() > 0) {
    		logger.debug("populateShardMongoClients already complete, skipping");
    	}

        for (Shard shard : shardsMap.values()) {
            String shardHost = shard.getHost();
            String seeds = StringUtils.substringAfter(shardHost, "/");
            
            logger.debug(name + " " + shard.getId() + " populateShardMongoClients() seeds: " + seeds);
            
            String[] seedHosts = seeds.split(",");
            
            List<ServerAddress> serverAddressList = new ArrayList<>();
            for (String seed : seedHosts) {
                String host = StringUtils.substringBefore(seed, ":");
                Integer port = Integer.parseInt(StringUtils.substringAfter(seed, ":"));
                
                serverAddressList.add(new ServerAddress(host, port));
            }
            
            MongoClientSettings.Builder settingsBuilder = MongoClientSettings.builder();
            settingsBuilder.applyToClusterSettings(builder -> builder.hosts(serverAddressList));
            if (connectionString.getSslEnabled() != null) {
                settingsBuilder.applyToSslSettings(builder -> builder.enabled(connectionString.getSslEnabled()));
            }
            if (connectionString.getCredential() != null) {
                settingsBuilder.credential(connectionString.getCredential());
            }
            MongoClientSettings settings = settingsBuilder.build();
            MongoClient mongoClient = MongoClients.create(settings);
            
            //logger.debug(String.format("%s isMaster started: %s", name, shardHost));
            Document isMasterResult = mongoClient.getDatabase("admin").runCommand(new Document("isMaster", 1));
            if (logger.isTraceEnabled()) {
                logger.trace(name + " isMaster complete, cluster: " + mongoClient.getClusterDescription());
            } else {
                //logger.debug(String.format("%s isMaster complete: %s", name, shardHost));
            }
            
            shardMongoClients.put(shard.getId(), mongoClient);
        }
    }
    
    public Document getLatestOplogEntry(String shardId) {
        MongoClient client = shardMongoClients.get(shardId);
        MongoCollection<Document> coll = client.getDatabase("local").getCollection("oplog.rs");
        Document doc = coll.find(ne("op", "n")).sort(eq("$natural", -1)).first();
        return doc;
    }

    public ShardTimestamp populateLatestOplogTimestamp(String shardId) {
        MongoClient client = shardMongoClients.get(shardId);
        MongoCollection<Document> coll = client.getDatabase("local").getCollection("oplog.rs");
        // ne("op", "n")
        Document doc = coll.find().projection(include("ts")).sort(eq("$natural", -1)).first();
        BsonTimestamp ts = (BsonTimestamp)doc.get("ts");
        ShardTimestamp st = new ShardTimestamp(shardId, ts);
        this.getShardsMap().get(shardId).setSyncStartTimestamp(st);
        return st;
    }

    /**
     * This will drop the db on each shard, config data will NOT be touched
     * 
     * @param dbName
     */
    public void dropDatabase(String dbName) {
        for (Map.Entry<String, MongoClient> entry : shardMongoClients.entrySet()) {
            logger.debug(name + " dropping " + dbName + " on " + entry.getKey());
            entry.getValue().getDatabase(dbName).drop();
        }
    }

    /**
     * This will drop the db on each shard, config data will NOT be touched
     * 
     * @param dbName
     */
    public void dropDatabases(List<String> databasesList) {
        for (Map.Entry<String, MongoClient> entry : shardMongoClients.entrySet()) {
            
            for (String dbName : databasesList) {
                if (! dbName.equals("admin")) {
                    logger.debug(name + " dropping " + dbName + " on " + entry.getKey());
                    entry.getValue().getDatabase(dbName).drop();
                }
                
            }
        }
    }
    
    private void dropForce(String dbName) {
        DeleteResult r = mongoClient.getDatabase("config").getCollection("collections").deleteMany(regex("_id", "^" + dbName + "\\."));
        logger.debug(String.format("Force deleted %s config.collections documents", r.getDeletedCount()));
        r = mongoClient.getDatabase("config").getCollection("chunks").deleteMany(regex("ns", "^" + dbName + "\\."));
        logger.debug(String.format("Force deleted %s config.chunks documents", r.getDeletedCount()));
    }

    public void dropDatabasesAndConfigMetadata(List<String> databasesList) {
        for (String dbName : databasesList) {
            if (! dbName.equals("admin")) {
                logger.debug(name + " dropping " + dbName);
                try {
                    mongoClient.getDatabase(dbName).drop();
                } catch (MongoCommandException mce) {
                    logger.debug("Drop failed, brute forcing.");
                    dropForce(dbName);
                }
               
            }
        }
    }
    
    public static Number getFastCollectionCount(MongoDatabase db, MongoCollection<RawBsonDocument> collection) {
        return collection.countDocuments();
    }
    
    public Number getCollectionCount(MongoDatabase db, MongoCollection<RawBsonDocument> collection) {
    	try {
    		BsonDocument result = collection.aggregate(countPipeline).first();
            Number count = null;
            if (result != null) {
                count = result.get("count").asNumber().longValue();
            }
            return count;
    	} catch (MongoCommandException mce) {
    		logger.error(name + " getCollectionCount error");
    		throw mce;
    	}
    }
    
    public Number getCollectionCount(String dbName, String collectionName) {
    	MongoDatabase db = mongoClient.getDatabase(dbName);
        return getCollectionCount(db, db.getCollection(collectionName, RawBsonDocument.class));
    }

    public Number getCollectionCount(MongoDatabase db, String collectionName) {
        return getCollectionCount(db, db.getCollection(collectionName, RawBsonDocument.class));
    }
    
    public MongoCollection<Document> getShardsCollection() {
        return configDb.getCollection("shards");
    }
    
    public MongoCollection<Document> getTagsCollection() {
        return configDb.getCollection("tags");
    }

    public MongoCollection<Document> getChunksCollection() {
        return configDb.getCollection("chunks");
    }
    
    public MongoCollection<RawBsonDocument> getChunksCollectionRaw() {
        return configDb.getCollection("chunks", RawBsonDocument.class);
    }
    
    public MongoCollection<RawBsonDocument> getChunksCollectionRawPrivileged() {
    	MongoDatabase configDb = csrsMongoClient.getDatabase("config");
        return configDb.getCollection("chunks", RawBsonDocument.class);
    }

    public MongoCollection<Document> getDatabasesCollection() {
        return configDb.getCollection("databases");
    }

    public void createDatabase(String databaseName) {
    	logger.debug(name + " createDatabase " + databaseName);
        String tmpName = "tmp_ShardConfigSync_" + System.currentTimeMillis();
        mongoClient.getDatabase(databaseName).createCollection(tmpName);
        
        // ugly hack
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        mongoClient.getDatabase(databaseName).getCollection(tmpName).drop();
    }
    
//    public void createDatabaseOnShards(String databaseName) {
//    	logger.debug(String.format("%s - createDatabaseOnShards(): %s", name, databaseName));
//    	
//    	
//        String tmpName = "tmp_ShardConfigSync_" + System.currentTimeMillis();
//        mongoClient.getDatabase(databaseName).createCollection(tmpName);
//        
//        // ugly hack
//        try {
//            Thread.sleep(1000);
//        } catch (InterruptedException e) {
//            // TODO Auto-generated catch block
//            e.printStackTrace();
//        }
//        mongoClient.getDatabase(databaseName).getCollection(tmpName).drop();
//    }
    
    public MongoIterable<String> listDatabaseNames() {
        return this.mongoClient.listDatabaseNames();
    }

    public MongoIterable<String> listCollectionNames(String databaseName) {
        return this.mongoClient.getDatabase(databaseName).listCollectionNames();
    }

    public void flushRouterConfig() {
        logger.debug(String.format("flushRouterConfig() for %s mongos routers", mongosMongoClients.size()));
        for (Map.Entry<String, MongoClient> entry : mongosMongoClients.entrySet()) {
            MongoClient client = entry.getValue();
            Document flushRouterConfig = new Document("flushRouterConfig", true);

            try {
                logger.debug(String.format("flushRouterConfig for mongos %s", entry.getKey()));
                client.getDatabase("admin").runCommand(flushRouterConfig);
            } catch (MongoTimeoutException timeout) {
                logger.debug("Timeout connecting", timeout);
            }
        }
    }

    public void stopBalancer() {
        if (versionArray.get(0) == 2 || (versionArray.get(0) == 3 && versionArray.get(1) <= 2)) {
            Document balancerId = new Document("_id", "balancer");
            Document setStopped = new Document("$set", new Document("stopped", true));
            UpdateOptions updateOptions = new UpdateOptions().upsert(true);
            configDb.getCollection("settings").updateOne(balancerId, setStopped, updateOptions);
        } else {
            adminCommand(new Document("balancerStop", 1));
        }
    }

    public Document adminCommand(Document command) {
        return mongoClient.getDatabase("admin").runCommand(command);
    }

    public String getVersion() {
        return version;
    }

    public List<Integer> getVersionArray() {
        return versionArray;
    }
    
    public boolean isVersion36OrLater() {
        if (versionArray.get(0) >= 4 || (versionArray.get(0) == 3 && versionArray.get(1) == 6)) {
            return true;
        }
        return false;
    }

    public Map<String, Shard> getShardsMap() {
        return shardsMap;
    }

    public Map<String, Document> getCollectionsMap() {
        return collectionsMap;
    }

    public MongoClient getMongoClient() {
        return mongoClient;
    }

    public MongoDatabase getConfigDb() {
        return configDb;
    }

    public Collection<MongoClient> getMongosMongoClients() {
        return mongosMongoClients.values();
    }
    
    public MongoClient getShardMongoClient(String shardId) {
    	// TODO clean this up
//    	if (!this.tertiaryShardsMap.isEmpty()) {
//    		String tid = tertiaryShardsMap.get(shardId).getId();
//    		return shardMongoClients.get(tid);
//    	} else {
//    		
//    	}
    	return shardMongoClients.get(shardId);
    }
    
    public Map<String, MongoClient> getShardMongoClients() {
        return shardMongoClients;
    }

    public void checkAutosplit() {
        logger.debug(String.format("checkAutosplit() for %s mongos routers", mongosMongoClients.size()));
        for (Map.Entry<String, MongoClient> entry : mongosMongoClients.entrySet()) {
            MongoClient client = entry.getValue();
            Document getCmdLine = new Document("getCmdLineOpts", true);
            Boolean autoSplit = null;
            try {
                // logger.debug(String.format("flushRouterConfig for mongos %s",
                // client.getAddress()));
                Document result = adminCommand(getCmdLine);
                Document parsed = (Document) result.get("parsed");
                Document sharding = (Document) parsed.get("sharding");
                if (sharding != null) {
                    sharding.getBoolean("autoSplit");
                }
                if (autoSplit != null && !autoSplit) {
                    logger.debug("autoSplit disabled for " + entry.getKey());
                } else {
                    logger.warn("autoSplit NOT disabled for " + entry.getKey());
                }
            } catch (MongoTimeoutException timeout) {
                logger.debug("Timeout connecting", timeout);
            }
        }
    }
    
    public void disableAutosplit() {
    	MongoDatabase configDb = mongoClient.getDatabase("config");
    	MongoCollection<RawBsonDocument> settings = configDb.getCollection("settings", RawBsonDocument.class);
    	Document update = new Document("$set", new Document("enabled", false));
    	settings.updateOne(eq("_id", "autosplit"), update);
    }
    
    public void compareCollectionUuids() {
    	logger.debug(String.format("%s - Starting compareCollectionUuids", name));
		populateShardMongoClients();

		List<String> dbNames = new ArrayList<>();
		listDatabaseNames().into(dbNames);
		
		Map<Namespace, Map<UUID, List<String>>> collectionUuidMappings = new TreeMap<>();

		for (Map.Entry<String, MongoClient> entry : getShardMongoClients().entrySet()) {
			MongoClient client = entry.getValue();
			String shardName = entry.getKey();

			for (String databaseName : client.listDatabaseNames()) {
				MongoDatabase db = client.getDatabase(databaseName);

				if (databaseName.equals("admin") || databaseName.equals("config") || databaseName.contentEquals("local")) {
					continue;
				}

				for (Document collectionInfo : db.listCollections()) {
					String collectionName = (String)collectionInfo.get("name");
					if (collectionName.endsWith(".create")) {
						continue;
					}
					Namespace ns = new Namespace(databaseName, collectionName);
					Document info = (Document) collectionInfo.get("info");
					UUID uuid = (UUID) info.get("uuid");
					
					Map<UUID, List<String>> uuidMapping = collectionUuidMappings.get(ns);
					if (uuidMapping == null) {
						uuidMapping = new TreeMap<>();
					}
					collectionUuidMappings.put(ns, uuidMapping);
					
					List<String> shardNames = uuidMapping.get(uuid);
					if (shardNames == null) {
						shardNames = new ArrayList<>();
					}
					uuidMapping.put(uuid, shardNames);
					shardNames.add(shardName);
					
					//logger.debug(entry.getKey() + " db: " + databaseName + "." + collectionName + " " + uuid);
				}
			}
		}
		
		int successCount = 0;
		int failureCount = 0;
		
		for (Map.Entry<Namespace, Map<UUID, List<String>>> mappingEntry : collectionUuidMappings.entrySet()) {
			Namespace ns = mappingEntry.getKey();
			Map<UUID, List<String>> uuidMappings = mappingEntry.getValue();
			if (uuidMappings.size() == 1) {
				successCount++;
				logger.debug(String.format("%s ==> %s", ns, uuidMappings));
			} else {
				failureCount++;
				logger.error(String.format("%s ==> %s", ns, uuidMappings));
			}
		}
		
		if (failureCount == 0 && successCount > 0) {
			logger.debug(String.format("%s - compareCollectionUuids complete: successCount: %s, failureCount: %s", name, successCount, failureCount));
		} else {
			logger.error(String.format("%s - compareCollectionUuids complete: successCount: %s, failureCount: %s", name, successCount, failureCount));
		}
		
    }
    
    public void createIndexes(String shardName, Namespace ns, Set<IndexSpec> sourceSpecs, boolean extendTtl) {
    	MongoClient client = getShardMongoClient(shardName);
    	MongoDatabase db = client.getDatabase(ns.getDatabaseName());
    	
    	MongoCollection<Document> c = db.getCollection(ns.getCollectionName());
		Document createIndexes = new Document("createIndexes", ns.getCollectionName());
		List<Document> indexes = new ArrayList<>();
		createIndexes.append("indexes", indexes);
		
		for (IndexSpec indexSpec : sourceSpecs) {
			logger.debug("ix: " + indexSpec);
			Document indexInfo = indexSpec.getSourceSpec().decode(codec);
			//BsonDocument indexInfo = indexSpec.getSourceSpec().clone();
			indexInfo.remove("v");
			Number expireAfterSeconds = (Number)indexInfo.get("expireAfterSeconds");
			if (expireAfterSeconds != null && extendTtl) {
				
				if (expireAfterSeconds.equals(0)) {
					logger.warn(String.format("Skip extending TTL for %s %s - expireAfterSeconds is 0 (wall clock exp.)", 
							ns, indexInfo.get("name")));
				} else {
					indexInfo.put("expireAfterSeconds", 50 * ShardConfigSync.SECONDS_IN_YEAR);
					logger.debug(String.format("Extending TTL for %s %s from %s to %s", 
							ns, indexInfo.get("name"), expireAfterSeconds, indexInfo.get("expireAfterSeconds")));
				}
				
			}
			indexes.add(indexInfo);
		}
		if (! indexes.isEmpty()) {
			
			try {
				Document createIndexesResult = db.runCommand(createIndexes);
				//logger.debug(String.format("%s result: %s", ns, createIndexesResult));
			} catch (MongoCommandException mce) {
				logger.error(String.format("%s createIndexes failed: %s", ns, mce.getMessage()));
			}
			
		}
    }
    
	public void findOrphans(boolean doMove) {

		logger.debug("Starting findOrphans");

		MongoCollection<RawBsonDocument> sourceChunksColl = getChunksCollectionRaw();
		FindIterable<RawBsonDocument> sourceChunks = sourceChunksColl.find().noCursorTimeout(true)
				.sort(Sorts.ascending("ns", "min"));

		String lastNs = null;
		int currentCount = 0;

		for (String shardId : shardsMap.keySet()) {
			
		}
		for (RawBsonDocument sourceChunk : sourceChunks) {
			String sourceNs = sourceChunk.getString("ns").getValue();

			if (!sourceNs.equals(lastNs)) {
				if (currentCount > 0) {
					logger.debug(String.format("findOrphans - %s - complete, queried %s chunks", lastNs,
							currentCount));
					currentCount = 0;
				}
				logger.debug(String.format("findOrphans - %s - starting", sourceNs));
			} else if (currentCount > 0 && currentCount % 10 == 0) {
				logger.debug(String.format("findOrphans - %s - currentCount: %s chunks", sourceNs, currentCount));
			}

			RawBsonDocument sourceMin = (RawBsonDocument) sourceChunk.get("min");
			RawBsonDocument sourceMax = (RawBsonDocument) sourceChunk.get("max");
			String sourceShard = sourceChunk.getString("shard").getValue();
			

			currentCount++;
			lastNs = sourceNs;
		}

		
	}


    public ConnectionString getConnectionString() {
        return connectionString;
    }

	public String getName() {
		return name;
	}

	public String getRsPattern() {
		return rsPattern;
	}

	public void setRsPattern(String rsPattern) {
		this.rsPattern = rsPattern;
	}

	public Map<String, Shard> getTertiaryShardsMap() {
		return tertiaryShardsMap;
	}

	public void setCsrsUri(String csrsUri) {
		this.csrsUri = csrsUri;
	}

	public boolean isMongos() {
		return mongos;
	}

}
