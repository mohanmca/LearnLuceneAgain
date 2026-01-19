# How Lucene Stores Text

Lucene stores text as an inverted index on disk, split into immutable segments, so search is fast, safe, and scalable.
Everything in the filesystem exists to support that idea.

## Step-by-Step: What Happens When Index is Created

### Step 1: You Start IndexWriter

```java
Directory directory = FSDirectory.open(indexPath);
IndexWriter writer = new IndexWriter(directory, config);
```

**What Lucene does:**
1.  Opens the folder
2.  Creates `write.lock`
3.  Checks for existing index

**Files created:**
*   `write.lock`

**Why useful?**
*   Prevents two writers corrupting index
*   Guarantees consistency

### Step 2: You Add a Document

```java
iwriter.addDocument(doc);
```

**What Lucene does in memory:**
1.  Analyzer breaks text: "This is the text to be indexed" → `[this, is, the, text, to, be, indexed]`
2.  Builds inverted index in RAM

*Conceptual Example:*
```text
this    → doc 0
is      → doc 0
text    → doc 0
indexed → doc 0
```

> **Note:** Nothing is written to disk yet.

### Step 3: You Close IndexWriter (Most Important)

```java
iwriter.close();
```

**What Lucene does:**
1.  Flushes RAM index to disk
2.  Creates one immutable segment
3.  Commits index

**Files created at First Commit:**
*   `_0.si`
*   `_0.cfs`
*   `_0.cfe`
*   `segments_1`

Let’s go file by file in creation order.

#### 1. `_0.si` — Segment Info File

**What it stores:**
Metadata about segment `_0`:
*   Number of documents
*   Field names (`fieldname`)
*   Field types (`TextField`)
*   Index version
*   Codec info

**Why useful?**
*   Lucene knows how to read the segment
*   Enables backward compatibility
*   Used during merges & search

> **Think:** Schema + Stats

#### 2. `_0.cfs` — Compound File (Actual Data)

**What it stores (inside):**
This is the real inverted index. Contains:
*   Term dictionary
*   Posting lists
*   Stored fields (your original text)
*   Norms (field length)
*   DocValues (if any)

*Internal Example:*
`"text" → [docID=0, freq=1]`

**Why useful?**
Search becomes `term → docs` instead of `doc → terms`.
Extremely fast lookups (O(log n)).

> **Think:** Google’s brain

#### 3. `_0.cfe` — Compound File Entries

**What it stores:**
Offsets & lengths of each internal file inside `.cfs`.

**Why useful?**
*   Allows random access
*   Lucene jumps directly to postings / fields

> **Think:** Index of the index

#### 4. `segments_1` — Index Manifest (Critical)

**What it stores:**
*   List of all segments
*   Commit version
*   Generation number
*   Deleted docs info

*Example:*
```text
segments_1
└─ _0
```

**Why useful?**
*   Entry point of index
*   Enables crash recovery
*   Supports atomic commits

> **Think:** Table of contents

### Step 4: You Search

```java
DirectoryReader.open(directory)
```

**What Lucene does:**
1.  Reads `segments_1`
2.  Finds segment `_0`
3.  Loads `_0.si`
4.  Reads `_0.cfs` using `_0.cfe`
5.  Finds postings for query terms

**Search is FAST because:**
*   No scanning
*   Direct `term → doc` mapping

## Why Lucene Uses This Filesystem Design

### 1. Speed 
*   Inverted index = instant lookup
*   Immutable segments = no locking during search

### 2. Safety 
*   Atomic commits
*   Crash-safe (`segments_N`)
*   Write lock protection

### 3. Scalability 
*   Segments can be merged
*   Millions of documents supported
*   Works on SSD, HDD, distributed FS

### 4. Near Real-Time Search 
*   New segments become searchable quickly
*   Old segments remain untouched

## What Happens When You Add More Documents?

1.  Run program again
2.  Creates `_1` (new segment)
3.  Updates `segments_2`
4.  Lucene searches both `_0` and `_1`.

*Later:*
`merge(_0 + _1) → _2`
Then deletes `_0`, `_1`.

## Summary Table

| File | Stores | Why |
| :--- | :--- | :--- |
| `write.lock` | Writer lock | Prevent corruption |
| `_*.si` | Segment metadata | Read & merge |
| `_*.cfs` | Inverted index data | Fast search |
| `_*.cfe` | Offset map | Random access |
| `segments_N` | Segment list | Entry point |

> **One-line mental model:**
> Lucene filesystem = append-only mini databases optimized for search.
