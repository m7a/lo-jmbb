package ma.jmbb;

import java.io.File;
import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

import ma.tools2.util.NotImplementedException;

class Restorer {

	private final PrintfIO        o;
	private final List<File>      src;
	private final File            dst;
	private final String          pattern;
	private final RestorationMode mode;
	private final int             version;

	/** @since JMBB 1.0.7 */
	private final boolean         findBlocksByFileName;
	/** @since JMBB 1.0.7 */
	private final long            useMetaBlock;

	Restorer(PrintfIO o, List<File> src, File dst, String pattern,
			RestorationMode mode, int version,
			boolean findBlocksByFileName, long useMetaBlock) {
		super();
		this.o                    = o;
		this.src                  = src;
		this.dst                  = dst;
		this.pattern              = pattern;
		this.mode                 = mode;
		this.version              = version;
		this.findBlocksByFileName = findBlocksByFileName;
		this.useMetaBlock         = useMetaBlock;
	}

	void run() throws MBBFailureException {
		mkdst();

		RDB[] sourceDBs = createDBForEachSource();
		RDB preliminaryMain = mergeDBs(sourceDBs);

		if(useMetaBlock == MetaBlockConverter.USE_META_BLOCK_NO) {
			restoreFromDB(preliminaryMain);
		} else {
			RDB newMain = new RCPIOMetaBlockExtractor(useMetaBlock,
				preliminaryMain, o).readDatabaseFromMetaBlock();
			// Use low level merge function to only merge such
			// blocks that are already known in `newMain` db.
			// This achieves consistent restores. Another option
			// would be to call the fully-fledged mergeDBs()
			// again, but this is not implemented yet.
			RAllBlockMap secondMerge = new RAllBlockMap(newMain);
			secondMerge.merge(preliminaryMain);
			restoreFromDB(newMain);
		}
	}

	private void mkdst() throws MBBFailureException {
		if(!dst.exists())
			if(!dst.mkdirs())
				throw new MBBFailureException("Unable to " +
					"create destination directory.");
	}

	private RDB[] createDBForEachSource() throws MBBFailureException {
		return new RDBCreator(o, src, findBlocksByFileName).
							createDBForEachSource();
	}

	private RDB mergeDBs(RDB[] sourceDBs) throws MBBFailureException {
		return new RDBMerger(o, mode).mergeDBs(sourceDBs);
	}

	private void restoreFromDB(DB db) throws MBBFailureException {
		Map<String, REntry> restorePaths = createRestorePathTable(db);
		Iterator<REntry> entries = restorePaths.values().iterator();

		if(mode == RestorationMode.LIST_VERSIONS_ONLY)
			listVersionsOnly(entries);
		else
			restoreNewest(db, entries, version != -1);
	}

	/**
	 * Creates a table of all files matching the pattern and all available
	 * versions of these files.
	 */
	private Map<String, REntry> createRestorePathTable(DB db) {
		Pattern regex = compileRegex();
		return createRestorePathTable(db, regex, version);
	}

	static Map<String, REntry> createRestorePathTable(DB db, Pattern regex,
								int version) {
		// Unsorted... we will sort after blocks later.
		Map<String, REntry> rpt = new HashMap<String, REntry>();

		for(DBBlock i: db.blocks) {
			Iterator<DBFile> containedFiles = i.getFileIterator();
			while(containedFiles.hasNext()) {
				DBFile j = containedFiles.next();
				addFileToRestorePathTable(rpt, regex, version,
									j, i);
			}
		}

		return rpt;
	}

	/**
	 * The result of this function is designed to be passed to
	 * <code>addFileToRestorePathTable(Map, Pattern, int, DBFile,
	 * DBBlock)</code> and <code>matchPattern(Pattern, DBFile)</code>
	 * which both accept null values to be passed for a simple
	 * "all input is ok".
	 *
	 * @return null if no pattern was wanted.
	 * @see #addFileToRestorePathTable(Map<String, REntry>, Pattern, DBFile,
	 * 	DBBlock)
	 * @see #matchPattern(Pattern, DBFile)
	 */
	private Pattern compileRegex() {
		if(pattern == null)
			return null;
		else
			return Pattern.compile(pattern);
	}

	/**
	 * Too many parameters but this function needed to be externalized.
	 *
	 * @param regex may be null if not used.
	 */
	private static void addFileToRestorePathTable(Map<String, REntry> rpt,
						Pattern regex, int version,
						DBFile file, DBBlock inBlk) {
		if(matchPattern(regex, file) && matchVersion(version, file) &&
							!file.isMeta()) {
			REntry alreadyHave = rpt.get(file.getPath());
			if(alreadyHave == null)
				rpt.put(file.getPath(),
						new REntry(file, inBlk));
			else
				alreadyHave.add(file, inBlk);
		}
	}

	/**
	 * @param regex always returns true if regex == null.
	 */
	private static boolean matchPattern(Pattern regex, DBFile file) {
		return regex == null || regex.matcher(file.getPath()).matches();
	}

	private static boolean matchVersion(int version, DBFile file) {
		return version == -1 || file.version == version;
	}

	private void listVersionsOnly(Iterator<REntry> entries) {
		while(entries.hasNext())
			entries.next().print(o);
	}

	private void restoreNewest(DB db, Iterator<REntry> entries,
						boolean considerObsolete)
						throws MBBFailureException {
		Map<Long, RGroup> tab = newTableOfFilesGroupBlocks(entries,
							considerObsolete);

		// It seemed to be a good idea to run this in paralle, but CPIO
		// does not want to do it (gives errors for file exists and no
		// such file or directory during restoration and restored data
		// is missing files). Should we find a backend process that
		// _can_ run in parallel, one might re-enable this code...

		//ExecutorService pool = Executors.newFixedThreadPool(
		//			Multithreading.determineThreadCount());

		for(RGroup i: tab.values()) {
			//pool.execute(new RCpioRestorer(db, dst, i, o));
			new RCpioRestorer(db, dst, i, o).run();
		}

		//pool.shutdown();
		//Multithreading.awaitPoolTermination(pool);
	}

	private static Map<Long, RGroup> newTableOfFilesGroupBlocks(
						Iterator<REntry> entries,
						boolean considerObsolete) {
		// TreeMap => sort by block id.
		Map<Long, RGroup> retTable = new TreeMap<Long, RGroup>();

		while(entries.hasNext())
			addEntryToGroupTable(retTable, entries.next(),
							considerObsolete);

		return retTable;
	}

	private static void addEntryToGroupTable(Map<Long, RGroup> tab,
					REntry e, boolean considerObsolete) {
		RFileVersionEntry sel = e.getSelectedForRestoration();
		if(sel == null || (sel.file.isObsolete() && !considerObsolete))
			return;

		RGroup grp = tab.get(sel.inBlk.getId());
		if(grp == null) {
			grp = new RGroup(sel.inBlk);
			tab.put(sel.inBlk.getId(), grp);
		}
		grp.add(sel.file);
	}

}
