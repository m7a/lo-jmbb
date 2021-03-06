#!/bin/sh -e
#
# TEST 2019/09/13
# This test creates a backup and adds a non-traversable subdirectory.
# As this directory is totally inacessible, it fails in the file traversal
# step which will be printed by JMBB but which will not cause the backup process
# to fail because apart from the non-readable files, the Backup could be
# created successfully. As it might be desirable that future JMBB versions
# issue an error code in this case (although continuing to backup is likely
# a good thing), this test issues a warning if recturn codes for this step
# change.
#
# This test passes as of JMBB 1.0.2.0

root="$(cd "$(dirname "$0")" && pwd)"
wd="/tmp/jmbbtest$$"
mkdir -p "$wd/in/1not_traversable"
trap "[ -n \"$JMBB_TRACE\" ] || { chmod 777 -R \"$wd\"; rm -r $wd; }" \
								INT TERM EXIT

# prepare
mkdir "$wd/in2" "$wd/out"
big4 -b "$wd/in/file1.bin"  29 MiB > "$wd/001file1.log"
big4 -b "$wd/in2/file2.bin" 29 MiB > "$wd/002file2.log"
echo testwort | jmbb -o "$wd/out" -i "$wd/in" "$wd/in2" > "$wd/003creat.log"

# update with "failure" directory
big4 -b "$wd/in/file3.bin" 50 MiB > "$wd/004file3.log"
big4 -b "$wd/in/1not_traversable/ne.bin" 1 MiB > "$wd/005nebin.log"
chmod 000 "$wd/in/1not_traversable"
rc1=0
"$root/p_invoke_jmbb.sh" -o "$wd/out" -i "$wd/in" "$wd/in2" \
				> "$wd/006jbbupdate.log" 2>&1 || rc1=$?

# attempt to restore
mkdir "$wd/restored"
rc2=0
"$root/p_invoke_jmbb.sh" -r "$wd/restored" -s "$wd/out" \
				> "$wd/007jmbbrestore.log" 2>&1 || rc2=$?

# expected result: restored data from previous (uninterrupted backup) and failed
# the update which had permission denied
passfile=0
files="$(find "$wd/restored$wd" -type f -name '*.bin' \
				-exec basename {} \; | sort | tr '\n' ' ')"
if [ "$files" = "file1.bin file2.bin file3.bin " ]; then
	if [ "$rc1" = 0 ]; then
		echo "[ OK ] t_feature_static_inaccessible"
		exit 0
	elif [ "$rc2" = 0 ]; then
		echo "[WARN] t_feature_static_inaccessible: rc1 changed to $rc1"
		exit 0
	else
		echo "[FAIL] t_feature_static_inaccessible:" \
					"restore OK, but fail indicated."
	fi
else
	echo "[FAIL] t_feature_static_inaccessible: DATA LOST." \
					"Wrongly restored: \"$files\""
	
fi

# in general, exit with error code unless test passed.
exit 1
