# Follow-up Questions

## 1. What is MMapDirectory? When to use it?

**MMapDirectory (Memory Mapped Directory)** is a specific implementation of Lucene's Directory abstraction that uses distinct OS memory mapping capabilities to read files.

Instead of reading a file by copying bytes from the disk into the Java heap (like `SimpleFSDirectory` or `NIOFSDirectory` might), `MMapDirectory` asks the Operating System to "map" the file contents directly into the process's virtual address space.

*   **Virtual Memory Magic:** The file appears to the program as if it's already in memory. When you access a specific byte, the OS detects if that "page" of memory is loaded. If it's not, the OS pauses the thread, fetches that page from disk, and then resumes—all transparently to Java.
*   **Zero Copy:** It avoids copying data from the OS kernel buffer to the Java user-space buffer, making it extremely fast.
*   **OS Caching:** It relies entirely on the OS file system cache (page cache). If you have free RAM, the OS will keep the index hot in memory for you.

### When to use it?

You should **almost always** use it on modern 64-bit systems.

1.  **You are on a 64-bit JVM:** The virtual address space is practically infinite ($2^{64}$), so mapping huge index files (GBs or TBs) is no problem.
2.  **You want maximum read performance:** It is generally the fastest way to access a Lucene index because of the reduced overhead.
3.  **You want to minimize Java Heap pressure:** Since the index data lives in the OS cache (off-heap), your JVM Garbage Collector doesn't have to manage those gigabytes of data.

> **Note on Windows:** In the past, `MMapDirectory` was tricky on Windows because memory-mapped files could not be deleted (which Lucene needs to do during merging). However, modern Lucene versions and Java updates have largely mitigated this. `FSDirectory.open()` will typically choose `MMapDirectory` safely on 64-bit Windows.

### Application to your code

You are currently using `FSDirectory.open(indexPath)` in `App.java` (Line 63), which is the **best practice**.

```java
// Your current code
try (Analyzer analyzer = new StandardAnalyzer();
    Directory directory = FSDirectory.open(indexPath)) { // <--- This line
```

`FSDirectory.open` is a smart factory. It checks your OS and Architecture (64-bit vs 32-bit) and automatically picks the best implementation.

*   On **64-bit Windows/Linux/macOS**, it usually returns `MMapDirectory`.
*   On **32-bit systems**, it usually returns `SimpleFSDirectory` or `NIOFSDirectory` to avoid running out of address space.

**To verify exactly what you are getting:**
You can simply print the class name in your code:

```java
System.out.println("Using Directory: " + directory.getClass().getSimpleName());
```

---

## 2. What is NIOFSDirectory? When to use it?

**NIOFSDirectory** uses Java's "New I/O" (NIO) APIs—specifically `java.nio.channels.FileChannel`—to read files from the file system.
It is a Directory implementation that performs **positional reads**.

*   **Thread Safety:** Unlike standard file I/O (used by `SimpleFSDirectory`), `NIOFSDirectory` does not need to move a "file pointer" to read data. It can say, "Read X bytes starting at position Y" directly.
*   **Concurrency:** Because it doesn't move a shared file pointer, multiple threads can read from the same file channel simultaneously without locking (synchronization).

### When to use it?

You generally use this when you cannot use `MMapDirectory` but still need good multi-threaded search performance.

1.  **Linux/Unix (32-bit):** If you are on a 32-bit, unix-like system, `MMapDirectory` is risky (limited address space), so `NIOFSDirectory` is the standard choice. It allows efficient concurrent searching without the locking overhead of `SimpleFSDirectory`.
2.  **Windows (Historical Context):** Historically, `NIOFSDirectory` was avoided on Windows because of a JVM implementation detail where positional reads were significantly slower than standard I/O. On Windows, Lucene's `FSDirectory.open` would typically fall back to `SimpleFSDirectory` if `MMapDirectory` wasn't an option.

### Summary of Directory Hierarchy

If you use `FSDirectory.open(path)`, Lucene makes the decision for you in this order:

1.  **MMapDirectory (Best):** The default for 64-bit systems. Uses virtual memory.
2.  **NIOFSDirectory:** The fallback for non-Windows operating systems (Linux, macOS) if MMap is not viable.
3.  **SimpleFSDirectory:** The fallback for Windows systems if MMap is not viable. It uses blocking `java.io.RandomAccessFile` and is the slowest for concurrent search because every read must be synchronized.

---

## 3. Why IndexWriter uses SeqNo?

Every time you perform an operation on an `IndexWriter` (like `addDocument`, `updateDocument`, or `deleteDocuments`), Lucene assigns that operation a unique, strictly increasing integer: the **Sequence Number**.

Think of it as a logical clock or a transaction ID for your index.

*   Operation 1 (Add Doc A) -> SeqNo: 1
*   Operation 2 (Delete Doc B) -> SeqNo: 2
*   Operation 3 (Update Doc A) -> SeqNo: 3

### Why does Lucene use it?

It solves the hard problems of **Concurrency** and **Replication**, especially in distributed systems (like Elasticsearch or Solr) that are built on top of Lucene.

#### A. Preventing "Stale" Reads (Versioning)
If you issue a write and immediately want to search for it, you need to know if your `IndexSearcher` is "fresh enough."

1.  You write a doc and get back **SeqNo: 100**.
2.  You can then check your `IndexSearcher`. If its "max completed sequence number" is only 99, you know that searcher does not contain your latest write yet. You can wait or reopen the searcher until it catches up to 100.

#### B. Replication & Recovery (The "Translog")
If you have a primary index and a backup (replica):

1.  The replica crashes and comes back online.
2.  Instead of copying the entire index again (which could be huge), the replica says: *"Hey, purely based on my files, the last operation I saw was SeqNo 500."*
3.  The primary says: *"Okay, I am currently at SeqNo 550. Here are just the operations from 501 to 550."*
    This makes syncing indexes dramatically faster (NRT - Near Real Time replication).

#### C. Conflict Resolution
If multiple threads or nodes try to update the same document:

*   Lucene ensures **strict ordering**. The operation that got the higher Sequence Number is effectively the "winner" that overwrites the previous state in the logical log of events.
