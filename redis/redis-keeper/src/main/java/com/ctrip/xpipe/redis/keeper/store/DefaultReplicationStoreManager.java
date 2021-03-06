package com.ctrip.xpipe.redis.keeper.store;



import com.ctrip.xpipe.concurrent.AbstractExceptionLogTask;
import com.ctrip.xpipe.redis.core.store.ReplicationStore;
import com.ctrip.xpipe.redis.core.store.ReplicationStoreManager;
import com.ctrip.xpipe.redis.keeper.config.KeeperConfig;
import com.ctrip.xpipe.utils.XpipeThreadFactory;


import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.Date;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * @author marsqing
 *
 *         May 31, 2016 5:33:46 PM
 */
public class DefaultReplicationStoreManager implements ReplicationStoreManager {

	private final static String META_FILE = "store_manager_meta.properties";

	private static final String LATEST_STORE_DIR = "latest.store.dir";

	private Logger logger = LoggerFactory.getLogger(getClass());

	private String clusterName;

	private String shardName;
	
	private String keeperRunid;

	private File baseDir;

	private File metaFile;

	private AtomicReference<Properties> currentMeta = new AtomicReference<Properties>();

	private AtomicReference<DefaultReplicationStore> currentStore = new AtomicReference<>();

	private KeeperConfig keeperConfig;
	
	private AtomicLong gcCount = new AtomicLong();
	
	private ScheduledFuture<?> gcFuture;

	public DefaultReplicationStoreManager(KeeperConfig keeperConfig, String clusterName, String shardName, String keeperRunid, File baseDir) {
		this.clusterName = clusterName;
		this.shardName = shardName;
		this.keeperRunid = keeperRunid;
		this.keeperConfig = keeperConfig;
		this.baseDir = new File(baseDir, clusterName + "/" + shardName);
		metaFile = new File(this.baseDir, META_FILE);

		gcFuture = Executors.newScheduledThreadPool(1, XpipeThreadFactory.create("gc", true)).scheduleWithFixedDelay(new AbstractExceptionLogTask() {
			
			@Override
			protected void doRun() throws Exception {
				gc();
			}
		}, keeperConfig.getReplicationStoreGcIntervalSeconds(), keeperConfig.getReplicationStoreGcIntervalSeconds(), TimeUnit.SECONDS);
	}

	@Override
	public synchronized ReplicationStore createIfNotExist() throws IOException {

		ReplicationStore currentReplicationStore = null;
		
		try{
			currentReplicationStore = getCurrent();
		}catch(Exception e){
			logger.error("[createIfNotExist]" + baseDir, e);
		}
		
		if (currentReplicationStore == null) {
			logger.info("[createIfNotExist]{}", baseDir);
			currentReplicationStore = create();
		}
		return currentReplicationStore;
	}
	
	@Override
	public synchronized ReplicationStore create(String masterRunid, long keeperBeginOffset) throws IOException {
		
		ReplicationStore replicationStore = create();
		
		replicationStore.getMetaStore().psyncBegun(masterRunid, keeperBeginOffset);
		
		return replicationStore;
	}

	@Override
	public synchronized DefaultReplicationStore create() throws IOException {

		File storeBaseDir = new File(baseDir, UUID.randomUUID().toString());
		storeBaseDir.mkdirs();

		logger.info("[create]{}", storeBaseDir);

		recrodLatestStore(storeBaseDir.getName());

		currentStore.set(new DefaultReplicationStore(storeBaseDir, keeperConfig, keeperRunid));
		return currentStore.get();
	}

	/**
	 * @param name
	 * @throws IOException
	 */
	private void recrodLatestStore(String storeDir) throws IOException {
		Properties meta = currentMeta();
		if (meta == null) {
			meta = new Properties();
		}

		meta.setProperty(LATEST_STORE_DIR, storeDir);

		saveMeta(meta);
	}

	/**
	 * @param meta
	 * @throws IOException
	 */
	private void saveMeta(Properties meta) throws IOException {
		try (Writer writer = new FileWriter(metaFile)) {
			meta.store(writer, null);
		}
		logger.info("[saveMeta][before]{}", currentMeta.get());
		currentMeta.set(meta);
		logger.info("[saveMeta][after]{}", currentMeta.get());
	}

	/**
	 * @return
	 * @throws IOException
	 */
	private Properties loadMeta() throws IOException {
		if (metaFile.isFile()) {
			Properties meta = new Properties();
			try (Reader reader = new FileReader(metaFile)) {
				meta.load(reader);
			}
			return meta;
		}

		return null;
	}

	private Properties currentMeta() throws IOException {
		
		return currentMeta(false);
	}

	private Properties currentMeta(boolean forceLoad) throws IOException {
		
		if(forceLoad || currentMeta.get() == null){
			currentMeta.set(loadMeta());
		}
		return currentMeta.get();
	}

	@Override
	public synchronized ReplicationStore getCurrent() throws IOException {
		// TODO read-write lock
		if (currentStore.get() == null) {
			Properties meta = currentMeta();
			if (meta != null) {
				if (meta.getProperty(LATEST_STORE_DIR) != null) {
					File latestStoreDir = new File(baseDir, meta.getProperty(LATEST_STORE_DIR));
					logger.info("[getCurrent][latest]{}", latestStoreDir);
					if (latestStoreDir.isDirectory()) {
						currentStore.set(new DefaultReplicationStore(latestStoreDir, keeperConfig, keeperRunid));
					}
				}
			}
		}

		ReplicationStore replicationStore = currentStore.get();
		if(replicationStore != null && !replicationStore.checkOk()){
			logger.info("[getCurrent][store not ok, return null]{}", replicationStore);
			return null;
		}
		return currentStore.get();
	}

	@Override
	public void destroy(ReplicationStore replicationStore) {
		// TODO Auto-generated method stub

	}

	@Override
	public String getClusterName() {
		return clusterName;
	}

	@Override
	public String getShardName() {
		return shardName;
	}

	protected synchronized void gc() throws IOException {
		
		logger.debug("[gc]{}", this);

		gcCount.incrementAndGet();
		Properties meta = currentMeta(true);
		final String currentDirName;
		if (meta != null) {
			currentDirName = meta.getProperty(LATEST_STORE_DIR);
			File[] replicationStoreDirs = baseDir.listFiles(new FileFilter() {

				@Override
				public boolean accept(File path) {
					return path.isDirectory() && !currentDirName.equals(path.getName());
				}
			});

			if (replicationStoreDirs != null && replicationStoreDirs.length > 0) {
				
				logger.info("[GC][old replicationstore]newest:{}", currentDirName);
				for (File dir : replicationStoreDirs) {
					if(System.currentTimeMillis() - dir.lastModified() > keeperConfig.getReplicationStoreMinTimeMilliToGcAfterCreate()){
						logger.info("[GC] directory {}", dir.getCanonicalPath());
						FileUtils.deleteDirectory(dir);
					}else{
						logger.warn("[GC][directory is created too short, do not gc]{}, {}", dir, new Date(dir.lastModified()));
					}
				}
			}
		}

		// gc current ReplicationStore
		ReplicationStore replicationStore = getCurrent();
		if (replicationStore != null) {
			replicationStore.gc();
		}
	}

	@Override
	public void destroy() {
		
		logger.info("[destroy]{}", this);
		
		if(gcFuture != null){
			logger.info("[destroy][cancel gc future]{}", this);
			gcFuture.cancel(true);
		}
		recursiveDelete(this.baseDir);
	}

	//helper method to clean created files
	private void recursiveDelete(File file) {
		if (!file.exists() || !file.canWrite()) {
			return;
		}
		if (file.isDirectory()) {
			File[] children = file.listFiles();
			if (children != null && children.length > 0) {
				for (File f : children) {
					recursiveDelete(f);
				}
			}
		}
		logger.info("[recursiveDelete]{}", file.getAbsolutePath());
		file.delete();
	}
	
	
	public long getGcCount() {
		return gcCount.get();
	}
	
	@Override
	public String toString() {
		return String.format("cluster:%s, shard:%s, keeperRunId:%s, baseDir:%s, currentMeta:%s", clusterName, shardName, keeperRunid, baseDir, 
				currentMeta.get() == null?"": currentMeta.get().toString());
	}
	
	public File getBaseDir() {
		return baseDir;
	}
}
