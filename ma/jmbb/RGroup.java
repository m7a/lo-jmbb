package ma.jmbb;

import java.io.*;
import java.nio.file.Files;
import java.util.ArrayList;

class RGroup {

	private final DBBlock blk;
	private final ArrayList<DBFile> files;

	RGroup(DBBlock blk) {
		super();
		this.blk = blk;
		files    = new ArrayList<DBFile>();
	}

	void add(DBFile f) {
		files.add(f);
	}

	String formatBlockId() {
		return blk.formatXMLBlockId();
	}

	ArrayList<DBFile> getFiles() {
		return files;
	}

	DBBlock getBlock() {
		return blk;
	}

}
