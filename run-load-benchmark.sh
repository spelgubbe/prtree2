java -jar build/libs/prtree-jmh.jar   '^org\.khelekore\.prtree\.PRTreeBuildBenchmark\.load$'   -p entryCount=100000   -p branchFactor=32   -wi 2   -i 3   -f 1   -prof jfr:dir=build/profiles/jf
