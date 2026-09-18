During my thesis project I have worked mainly on implementing methods for modifying an R-tree in the PR-tree code found at http://khelekore.org/prtree, and performing experiments to see which one should be preferred when the objective is to maintain fast query performance. The PR-tree is a special R-tree (a common spatial index) that has a performance guarantee for spatial queries. The PR-tree was not modifiable before, and building the spatial index could be very slow for larger inputs. 

## Building

The project uses the checked-in Gradle Wrapper, so no system-wide Gradle installation is required. A JDK 17 or newer is required; generated class files target Java 17.

```shell
./gradlew build
```

The normal build runs the correctness-focused JUnit suite and creates the library, source, and Javadoc JARs under `build/libs`. The original long-running and hand-timed tests remain available separately:

```shell
./gradlew historicalTest
```

On Windows, use `gradlew.bat` in place of `./gradlew`.

The changes I have made do not affect any existing code using the PR-tree or its behavior except for the fact that arrays of objects in nodes, have been exchanged for ArrayLists. This adds a constant memory overhead per node (for input size n there are about n/B nodes, where B is typically 8 or larger).

Modifications to the new R-tree, being insertions or deletions, can be done with one of two policies. Either the algorithms specified by Guttman (a standard R-tree) are used, or the algorithms specified by Beckmann et al. (2000) (called the R*-tree) are used. The R*-tree algorithms is set to be the default as it was found to produce higher quality R-trees for query (find) performance, during the thesis project.

Insertions can be performed in logarithmic time in the size of the tree using either one of these policies. There are, however, hidden constants making performing O(n) insertions a bad idea.

Deletions can be performed in the same time it takes to query for an object in the tree, so worst case linear time. If someone really wants to delete many things, you can do it as fast as insertions with smaller changes.

All operations have been implemented to respect a concurrency policy, which defaults to all operations being concurrency safe. Performing queries can be done, from multiple threads simulataneously. Inserting and deleting elements will be forced to happen sequentially, even if performed from multiple threads. A read/write lock is used for this. Using a lazy iterator, however, does not work while concurrently modifying the tree (that throws a concurrent modification exception).

In addition, I have written several new methods for building the PR-tree. These all produce tree structures that are PR-trees, but may have very slight differences. The algorithmic complexity of them all are equal, O(n log n). However, the new methods are faster due to reasons like memory locality and simplicity and parallelism. The new methods are inspired by the kd-tree building algorithm.  The speedup has been measured to be between 1 and 10. The speedup is larger the bigger the input size is, due to low memory locality in the original build algorithm (random accesses become very slow with large input sizes). The speedup may also get higher with more cores. All of these algorithms seem memory bottlenecked, so larger cpu caches improve performance a lot.

![Benchmarks of PR-tree builders](https://i.imgur.com/UWQ2zwP.png 'graph')

*Algorithm #3 and parallel #3 are what runs when calling PRTree::load and PRTree::loadParallel, respectively. Algorithm #1 runs when calling PRTree::load, this is the original building algorithm.*
