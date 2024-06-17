package depends;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.HttpHeaders;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * @author : hannasong
 * @version : 1.0
 * @date : Created in 16:31 2024/6/17
 * @description : 上传dify知识库
 */
public class TextSegmentUploader {

    private static final String API_KEY = "dataset-o1lNG1VIQrhpHWotn8RA0vTR";
    private static final String BASE_URL = "http://10.6.56.26/v1";
    private static String DATASET_ID = "949f896b-f22f-4442-beca-6a096c931de0";
    private static String DOCUMENT_ID = "241e9e5c-615a-4c49-adc5-23e958424a75";

    public static void main(String[] args) {
        String dirPath = "/Users/esvc/biyao/public3rd/depends/output/express.biyao.com.part";
        callDify(dirPath);
    }

    private static void callDify(String dirPath) {
        String url = "http://10.6.56.26/datasets/949f896b-f22f-4442-beca-6a096c931de0/documents/a9226bf4-28c3-40a0-94d2-bca2b43bdc55";
        if(url!=null && !url.isEmpty()){
            String[] parts = url.split("/");
            DATASET_ID = parts[4];
            DOCUMENT_ID = parts[6];
        }
        File dir = new File(dirPath);
        if (!dir.exists() || !dir.isDirectory()) {
            System.out.println("Invalid directory path: " + dirPath);
            return;
        }

        for (File file : dir.listFiles()) {
            if (file.isFile()) {
                try (BufferedReader br = new BufferedReader(new FileReader(file))) {
                    StringBuilder contentBuilder = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) {
                        contentBuilder.append(line).append("\n");
                    }
                    String content = contentBuilder.toString();
                    System.out.println(file.getName());
                    addDocumentSegment(DATASET_ID, DOCUMENT_ID, content, new JSONObject(), "");
                } catch (IOException e) {
                    System.out.println("Error processing file: " + file.getName() + ", Error: " + e.getMessage() + ". Skipping to next file.");
                }
            }
        }
    }

    private static void addDocumentSegment(String datasetId, String documentId, String content, JSONObject keywords, String answer) {
        String url = BASE_URL + "/datasets/" + datasetId + "/documents/" + documentId + "/segments";

        JSONObject segment = new JSONObject();
        segment.put("content", content);
        segment.put("answer", answer);
        segment.put("keywords", keywords);

        JSONArray segments = new JSONArray();
        segments.add(segment);

        JSONObject payload = new JSONObject();
        payload.put("segments", segments);

        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            HttpPost post = new HttpPost(url);
            post.setHeader(HttpHeaders.AUTHORIZATION, "Bearer " + API_KEY);
            post.setHeader("Content-Type", "application/json");
            post.setEntity(new StringEntity(payload.toJSONString(), StandardCharsets.UTF_8));

            try (CloseableHttpResponse response = httpClient.execute(post)) {
                String responseString = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            System.out.println("Error sending request to " + url + ", Error: " + e.getMessage());
        }
    }
}

