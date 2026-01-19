package com.example.lucene;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.FuzzyQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.PrefixQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;

public class App {
  public static void main(String[] args) throws Exception {
    if (args.length < 2) {
      System.out.println("Usage: mvn -q -DskipTests compile exec:java -Dexec.args=\"<path-to-text-file> <query>\"");
      System.out.println("Example: mvn -q -DskipTests compile exec:java -Dexec.args=\"data/sample.txt lucene\"");
      System.out.println("Example: mvn -q -DskipTests compile exec:java -Dexec.args=\"src/test/resources/gandhi.txt satyagraha\"");
      return;
    }

    Path filePath = Paths.get(args[0]);
    String queryText = args[1];

    if (!Files.isRegularFile(filePath)) {
      System.err.println("File not found: " + filePath);
      return;
    }

    // Keep the index inside the project directory so the files are easy to inspect.
    Path indexPath = Paths.get("index").toAbsolutePath();
    Files.createDirectories(indexPath);

    // StandardAnalyzer lowercases terms and removes common English stop words.
    // Use the same analyzer for indexing and searching to ensure tokens match.
    try (Analyzer analyzer = new StandardAnalyzer();
         Directory directory = FSDirectory.open(indexPath)) {
      boolean indexed = false;
      if (needsIndexing(directory, filePath)) {
        System.out.println("Indexing file: " + filePath);
        indexFile(directory, analyzer, filePath);
        indexed = true;
      } else {
        System.out.println("Index is up to date for file; using existing index at: " + indexPath);
      }
      if (indexed) {
        printIndexDiagnostics(directory, indexPath);
      }
      search(directory, analyzer, queryText);
    }
  }

  private static void indexFile(Directory directory, Analyzer analyzer, Path filePath) throws IOException {
    String content = Files.readString(filePath, StandardCharsets.UTF_8);
    long lastModified = Files.getLastModifiedTime(filePath).toMillis();
    long fileSize = Files.size(filePath);

    // IndexWriterConfig wires the analyzer into the indexing pipeline.
    // Lucene builds an inverted index: terms -> list of documents containing them.
    IndexWriterConfig config = new IndexWriterConfig(analyzer);
    // Keep existing documents when present; update by path if the file changed.
    config.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
    try (IndexWriter writer = new IndexWriter(directory, config)) {
      Document doc = new Document();
      doc.add(new StringField("path", filePath.toString(), Field.Store.YES));
      // TextField stores and analyzes content so it can be searched and displayed.
      doc.add(new TextField("content", content, Field.Store.YES));
      // Store metadata so we can skip re-indexing when the file is unchanged.
      doc.add(new StoredField("lastModified", lastModified));
      doc.add(new StoredField("size", fileSize));

      writer.updateDocument(new Term("path", filePath.toString()), doc);
      writer.commit();
    }
  }

  private static boolean needsIndexing(Directory directory, Path filePath) throws IOException {
    if (!DirectoryReader.indexExists(directory)) {
      return true;
    }

    try (DirectoryReader reader = DirectoryReader.open(directory)) {
      IndexSearcher searcher = new IndexSearcher(reader);
      TopDocs docs = searcher.search(new TermQuery(new Term("path", filePath.toString())), 1);
      if (docs.totalHits.value == 0) {
        return true;
      }


      Document doc = searcher.doc(docs.scoreDocs[0].doc);
      if (doc.getField("lastModified") == null || doc.getField("size") == null) {
        return true;
      }
      long indexedLastModified = doc.getField("lastModified").numericValue().longValue();
      long indexedSize = doc.getField("size").numericValue().longValue();

      long currentLastModified = Files.getLastModifiedTime(filePath).toMillis();
      long currentSize = Files.size(filePath);

      return indexedLastModified != currentLastModified || indexedSize != currentSize;
    }
  }

  private static void printIndexDiagnostics(Directory directory, Path indexPath) throws IOException {
    System.out.println("Index directory: " + indexPath);
    String[] files = directory.listAll();
    System.out.println("Index files (" + files.length + "):");
    for (String file : files) {
      System.out.println("  " + file + " (" + directory.fileLength(file) + " bytes)");
    }
  }

  private static void search(Directory directory, Analyzer analyzer, String queryText) throws Exception {
    // Show how the query text becomes tokens that Lucene actually searches for.
    System.out.println("Analyzer tokens for query:");
    for (String token : analyzeText(analyzer, "content", queryText)) {
      System.out.println("  " + token);
    }

    // QueryParser applies the same analyzer to build the final query.
    // The parsed query makes visible the actual terms and operators Lucene will use.
    Query query = buildQuery(analyzer, "content", queryText);
    System.out.println("Parsed query: " + query);

    try (DirectoryReader reader = DirectoryReader.open(directory)) {
      // IndexSearcher executes the query and scores with BM25 by default.
      IndexSearcher searcher = new IndexSearcher(reader);
      TopDocs topDocs = searcher.search(query, 10);

      System.out.println("Total hits: " + topDocs.totalHits.value);
      for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
        Document doc = searcher.doc(scoreDoc.doc);
        System.out.println("Hit: " + doc.get("path") + " (score=" + scoreDoc.score + ")");
        System.out.println("  " + preview(doc.get("content"), 160));
      }
    }
  }

  private static List<String> analyzeText(Analyzer analyzer, String field, String text) throws IOException {
    List<String> tokens = new ArrayList<>();
    try (TokenStream stream = analyzer.tokenStream(field, text)) {
      CharTermAttribute term = stream.addAttribute(CharTermAttribute.class);
      stream.reset();
      while (stream.incrementToken()) {
        tokens.add(term.toString());
      }
      stream.end();
    }
    return tokens;
  }

  private static String preview(String content, int maxLen) {
    if (content == null) {
      return "";
    }
    String cleaned = content.replaceAll("\\s+", " ").trim();
    if (cleaned.length() <= maxLen) {
      return cleaned;
    }
    return cleaned.substring(0, maxLen) + "...";
  }

  private static Query buildQuery(Analyzer analyzer, String field, String queryText) throws Exception {
    if (isSimpleTerm(queryText)) {
      List<String> tokens = analyzeText(analyzer, field, queryText);
      if (tokens.size() == 1) {
        String term = tokens.get(0);
        BooleanQuery.Builder builder = new BooleanQuery.Builder();
        builder.add(new PrefixQuery(new Term(field, term)), BooleanClause.Occur.SHOULD);
        builder.add(new FuzzyQuery(new Term(field, term), 2), BooleanClause.Occur.SHOULD);
        return builder.build();
      }
    }
    QueryParser parser = new QueryParser(field, analyzer);
    return parser.parse(queryText);
  }

  private static boolean isSimpleTerm(String text) {
    return text != null && text.matches("[A-Za-z0-9]+");
  }
}
