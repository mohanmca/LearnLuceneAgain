# Searching in Lucene

Searching is the process of looking up terms in the **Inverted Index** to find matching documents and ranking them by relevance.

> **Analogy:** If Indexing is writing the book's index, **Searching** is you looking up "Photosynthesis" and deciding which page is the most useful.

## 1. The Search Workflow

When a user searches, the following chain of events happens in milliseconds:

**User Query** ➝ **QueryParser** ➝ **Query Object** ➝ **IndexSearcher** ➝ **TopDocs**

1.  **User Query:** The user types `"fast fox"`.
2.  **QueryParser:** Parses the string into a Lucene Query Object.
    *   Example: `AnalyzedQuery` becomes `TermQuery("fast") OR TermQuery("fox")` (depending on default operator).
3.  **IndexSearcher:** The core class that coordinates the search using the `DirectoryReader`.
4.  **Weight & Scorer:**
    *   **Weight:** Calculates the importance of terms (statistics like "how rare is 'fox'?").
    *   **Scorer:** Iterates over matching documents and computes a score.
5.  **Collector:** As the Scorer finds matches, the Collector keeps only the best N results (e.g., Top 10) in a Priority Queue.

## 2. Under the Hood: How Searching Works

Let's say we search for **"quick dog"**.

### Step A: Term Lookup
Lucene looks at the **Term Dictionary** (in memory/disk).

*   Look up **"quick"** ➝ Found! Points to Postings List `[Doc 1, Doc 2]`.
*   Look up **"dog"** ➝ Found! Points to Postings List `[Doc 2]`.

### Step B: Postings Traversal (Intersection/Union)
Depending on your query logic:

*   **OR Query ("quick" OR "dog"):**
    *   Union of `[1, 2]` and `[2]`.
    *   Result: `Doc 1, Doc 2`.
*   **AND Query ("quick" AND "dog"):**
    *   Intersection of `[1, 2]` and `[2]`.
    *   Result: `Doc 2`.

> **Speed Secret:** Lucene efficiently skips through these lists (using **Skip Lists**) so it doesn't have to read every single ID if the list is long.

## 3. Scoring (Why result X is better than Y)

Lucene doesn't just find documents; it ranks them using a similarity formula (default is **BM25**).

**Key Factors in Scoring:**
1.  **TF (Term Frequency):** How often does "fox" appear in this document? (More is better).
2.  **IDF (Inverse Document Frequency):** How rare is "fox" across all documents? (Rarer terms constitute stronger matches).
3.  **Field Length:** Is the match in a short title (stronger) or a long body of text (weaker)?

## 4. Which Files are Used?

During search, `IndexSearcher` reads from the same segment files created during indexing:

| File | Role in Search |
| :--- | :--- |
| **.tim / .tip** | **Term Dictionary:** Used to find if the query terms exist. |
| **.doc** | **Postings:** Used to find which documents contain the terms. |
| **.pos** | **Positions:** Used if searching for a phrase "quick brown" (to ensure words are adjacent). |
| **.nvm / .dvd** | **Norms/DocValues:** Used for calculating the "Relevance Score" or for sorting/faceting. |

## Summary
Searching is essentially:
1.  **Lookup** terms in the Dictionary.
2.  **Retrieve** lists of documents.
3.  **Calculate** a score for each match.
4.  **Sort** by highest score.
