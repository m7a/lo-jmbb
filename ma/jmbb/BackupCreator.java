package ma.jmbb;

import java.io.*;

import java.util.ArrayList;
import java.util.List;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

class BackupCreator {

	private final List<File> src;
	private final File dst;
	private final PrintfIO o;

	private BCDB db;
	private BCRawFileScanner scanner;
	private BCBlockCreator creator;

	private ArrayList<BCChangedFile> nextBlockFiles;
	// Can be excceeded if a single file is bigger than this.
	private long normalMaxBlockSize;
	private long nextBlockSize;

	BackupCreator(final PrintfIO o, final File dst, final List<File> src)
						throws MBBFailureException {
		super();
		this.o   = o;
		this.dst = dst;
		this.src = src;
		ensureValidDestination();
		checkSources();
	}

	private void ensureValidDestination() throws MBBFailureException {
		if(!dst.exists()) {
			if(!dst.mkdirs()) {
				throw new MBBFailureException("Failed to " +
					"create destination directory.");
			}
		} else if(!dst.isDirectory()) {
			throw new MBBFailureException("Destination is not a " +
								"directory.");
		} else if(!dst.canWrite()) {
			throw new MBBFailureException("Destination directory " +
							"is not writeable.");
		}
	}

	private void checkSources() throws MBBFailureException {
		for(File i: src) {
			if(!i.exists() || !i.canRead()) {
				throw new MBBFailureException("File " +
					i.toString() + " can not be read.");
			}
		}
	}

	void run() throws MBBFailureException {
		scanner = new BCRawFileScanner(o, src);
		scanner.start();

		db = new BCDB(dst.toPath(), o);
		db.initFromLoc();
		db.createRedundantUtilityDataStructure();

		normalMaxBlockSize = db.header.getBlocksizeKiB() * 1024;
		nextBlockFiles     = new ArrayList<BCChangedFile>();
		nextBlockSize      = 0;

		// The two stage finish system explained / New as of 2019/09/14
		// processFiles() may fail e.g. if there are files that have
		// potentially changes but which are no longer readable. This
		// may cause the processStat()-function to fail which finally
		// results in an MBBfailureException being thrown by
		// processFiles().
		//
		// The intended behavior for this case is:
		// Await child process termination, then terminate JMBB with
		// an error code and without chainging the database and
		// without deleting obsolete blocks.

		creator = new BCBlockCreator(o, db);
		try {
			processFiles();
		} catch(MBBFailureException ex) {
			throw ex;
		} finally {
			creator.finish();
		}

		scanner.throwPossibleFailure();

		saveDB();

		// New 2019/09/14 delete blocks after writing DB for
		// better transactionality (if something failed, no blocks
		// are lost under no circumstances)
		db.blocks.deleteNewlyObsolete(o);
	}

	private void processFiles() throws MBBFailureException {
		Stat s;
		try {
			while((s = scanner.requestNextEntry()) != null)
				processStat(s, false);
		} catch(InterruptedException ex) {
			throw new MBBFailureException("Unexpected interrupt.",
									ex);
		}

		addMetaFileIfNecessary();
		processRemainingFilesIfAny();
	}

	private void processStat(Stat s, boolean meta)
						throws MBBFailureException {
		BCChangedFile chg = db.acquireChangedFileIfNecessary(s, meta,
									o);
		if(chg != null)
			addToABlock(chg);
	}

	private void addToABlock(BCChangedFile chg)
						throws MBBFailureException {
		if(nextBlockSize != 0 && nextBlockSize + chg.change.size
							> normalMaxBlockSize) {
			newBlock();
		}
		nextBlockFiles.add(chg);
		nextBlockSize += chg.change.size;
	}

	private void newBlock() throws MBBFailureException {
		creator.scheduleCreation(nextBlockFiles, nextBlockSize);
		nextBlockFiles = new ArrayList<BCChangedFile>();
		nextBlockSize = 0;
	}

	private void addMetaFileIfNecessary() throws MBBFailureException {
		if(db.blocks.isAddingMetaFileNecessary(db)) {
			try {
				addMetaFile();
			} catch(IOException ex) {
				throw new MBBFailureException("Could not " +
						"read database file.", ex);
			}
		} else {
			creator.setRemoveMetaFilesFromObsoletionList();
		}
	}

	private void addMetaFile() throws IOException, MBBFailureException {
		Path dbFile = db.getDBFile();
		BasicFileAttributes a = Files.readAttributes(dbFile,
						BasicFileAttributes.class);
		Stat s = scanner.createStat(dbFile, a);
		processStat(s, true);
	}

	private void processRemainingFilesIfAny() throws MBBFailureException {
		if(!nextBlockFiles.isEmpty()) {
			newBlock();
		}
	}

	private void saveDB() throws MBBFailureException {
		try {
			db.save();
		} catch(IOException ex) {
			throw new MBBFailureException(ex);
		}
	}

}
