import java.io.*;
import java.net.*;

public class test_ollama {
  public static void main(String[] args) throws Exception {
    URL url = new URL("http://host.docker.internal:11434/api/tags");
    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
    conn.setConnectTimeout(5000);
    conn.setReadTimeout(5000);
    int code = conn.getResponseCode();
    System.out.println("Response code: " + code);
    try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
      String line;
      while ((line = br.readLine()) != null) System.out.println(line);
    }
  }
}
