# The Inverted Index

The **Inverted Index** is the heart of Lucene. It is a data structure optimized for fast full-text search.

> **Analogy:** Think of a **Book Index** at the back of a textbook.
> *   If you want to find every page where the word "Photosynthesis" appears, you don't read the whole book (scan).
> *   You go to the Index, find "Photosynthesis", and it gives you the page numbers: `5, 23, 108`.
> *   You jump directly to those pages.

## 1. How Indexing is Done (The Process)

When you add a document to Lucene, it goes through a pipeline before hitting the disk.

**Input Text** ➝ **Analyzer** ➝ **Tokens** ➝ **Index Chain** ➝ **Segment (Disk)**

1.  **Input:** You provide the raw text.
2.  **Analysis:** The `Analyzer` breaks text into individual words (**tokens**) and normalizes them (lowercase, remove punctuation, etc.).
3.  **Indexing:** Lucene builds the inverted index structure in memory (buffer).
4.  **Flush/Commit:** The in-memory buffer is written to disk as a **Segment**.

## 2. Working Example

Let's index two simple documents.

*   **Doc 1:** "The quick brown fox jumps"
*   **Doc 2:** "The quick brown dog runs"

### Step A: Analysis (Tokenization)
The analyzer (e.g., `StandardAnalyzer`) breaks them down and lowercases them. Common words like "the" might be removed if StopWords are enabled, but let's assume we keep them for this example.

*   **Doc 1:** `[the, quick, brown, fox, jumps]`
*   **Doc 2:** `[the, quick, brown, dog, runs]`

### Step B: The Inverted Index Structure
Lucene turns this into two main components: the **Term Dictionary** and the **Posting Lists**.

#### 1. Term Dictionary
A sorted list of all unique terms across all documents.

#### 2. Posting Lists
For each term, a list of Document IDs (DocIDs) where that term appears.

| Term | Frequency | Posting List (DocIDs) |
| :--- | :---: | :--- |
| **brown** | 2 | `[Doc 1, Doc 2]` |
| **dog** | 1 | `[Doc 2]` |
| **fox** | 1 | `[Doc 1]` |
| **jumps** | 1 | `[Doc 1]` |
| **quick** | 2 | `[Doc 1, Doc 2]` |
| **runs** | 1 | `[Doc 2]` |
| **the** | 2 | `[Doc 1, Doc 2]` |

### How Search Uses This
If you search for **"quick dog"**:
1.  Lucene looks up **"quick"** ➝ Found in `[Doc 1, Doc 2]`
2.  Lucene looks up **"dog"** ➝ Found in `[Doc 2]`
3.  Intersection (AND query): **Doc 2**

## 3. Which Files Store This?

Inside a Lucene Segment (e.g., `_0.cfs`), these logical structures map to specific internal files.

| Component | Extensions | Description |
| :--- | :--- | :--- |
| **Term Dictionary** | `.tim`, `.tip` | Stores the sorted terms (like the words in the book index). `.tip` is an index into the `.tim` file for faster lookup. |
| **Postings** | `.doc` | Stores the list of DocIDs for each term. |
| **Positions** | `.pos` | Stores the position of terms in the document (e.g., "fox" is the 4th word). Used for phrase search ("quick brown"). |
| **Payloads/Offsets** | `.pay` | Stores character offsets (start/end) for highlighting search results. |

> **Note:** In modern Lucene, usually all these small files are packed into a single **Compound File** (`.cfs`) to save open file descriptors, but logically they exist as separate components.
