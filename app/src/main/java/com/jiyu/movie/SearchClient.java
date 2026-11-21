package com.jiyu.movie;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import org.json.*;

final class SearchClient {
    static JSONObject search(String origin, String term, int page) throws Exception {
        String host = new URL(origin).getHost().replaceFirst("^www\\.", "");
        String tags = URLEncoder.encode(term, "UTF-8");
        URL url = new URL("https://rankv21." + host + "/v3/list/briefsearch?tags=" + tags
            + "&cinema=1&page=" + page + "&size=10&orderby=4&desc=0");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(12000);
            connection.setReadTimeout(20000);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Origin", origin);
            connection.setRequestProperty("Referer", origin + "/");
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36");
            connection.setDoOutput(true);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(new JSONObject().put("tags", term).toString().getBytes(StandardCharsets.UTF_8));
            }
            StringBuilder body = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                for (String line; (line = reader.readLine()) != null;) body.append(line);
            }
            JSONObject response = new JSONObject(body.toString()).getJSONObject("data");
            if (response.getInt("code") != 0) throw new IOException("Search unavailable");
            JSONObject group = response.getJSONArray("info").optJSONObject(0);
            JSONArray items = new JSONArray();
            int count = 0;
            if (group != null) {
                count = group.optInt("recordcount");
                JSONArray rows = group.getJSONArray("result");
                for (int i = 0; i < rows.length(); i++) {
                    JSONObject row = rows.getJSONObject(i);
                    String id = row.optString("contxt");
                    String title = row.optString("title").trim();
                    if (!id.matches("[A-Za-z0-9_-]+") || title.isEmpty()) continue;
                    items.put(new JSONObject().put("name", title).put("url", origin + "/play/" + id));
                }
            }
            return new JSONObject().put("items", items).put("previous", page > 1).put("next", count > page * 10);
        } finally {
            connection.disconnect();
        }
    }
}
