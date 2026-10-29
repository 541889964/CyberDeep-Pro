package com.cyber.deep;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    private RecyclerView recyclerView;
    private EditText etInput;
    private TextView tvStatus, tvTitle;
    private WebView webView;
    private LinearLayout nativeLayout;

    private boolean isRunning = false;
    private boolean isPageHooked = false;

    private MsgAdapter adapter;
    private final List<Msg> msgList = new ArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final StringBuilder sseBuffer = new StringBuilder();
    
    // 防抖节流机制
    private final StringBuilder pendingAiText = new StringBuilder();
    private boolean isUiUpdatePending = false;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        recyclerView = findViewById(R.id.recyclerView);
        etInput = findViewById(R.id.etInput);
        ImageButton btnSend = findViewById(R.id.btnSend);
        webView = findViewById(R.id.webView);
        nativeLayout = findViewById(R.id.nativeLayout);
        tvStatus = findViewById(R.id.tvStatus);
        tvTitle = findViewById(R.id.tvTitle);

        adapter = new MsgAdapter();
        LinearLayoutManager lm = new LinearLayoutManager(this);
        lm.setStackFromEnd(true); // 列表从底部开始排版
        recyclerView.setLayoutManager(lm);
        recyclerView.setAdapter(adapter);

        initWebView();

        // 长按顶部栏，无缝切换 网页登录 和 原生界面
        tvTitle.setOnLongClickListener(v -> {
            if (webView.getVisibility() == View.VISIBLE) {
                webView.setVisibility(View.GONE);
                nativeLayout.setVisibility(View.VISIBLE);
                Toast.makeText(this, "已切换到原生界面", Toast.LENGTH_SHORT).show();
            } else {
                webView.setVisibility(View.VISIBLE);
                nativeLayout.setVisibility(View.GONE);
                Toast.makeText(this, "请刷新或重新登录", Toast.LENGTH_SHORT).show();
            }
            return true;
        });

        btnSend.setOnClickListener(v -> {
            String prompt = etInput.getText().toString().trim();
            if (TextUtils.isEmpty(prompt) || isRunning || !isPageHooked) return;
            etInput.setText("");
            addMsg("User", prompt);
            isRunning = true;
            tvStatus.setText("正在思考...");
            sseBuffer.setLength(0);
            pendingAiText.setLength(0);

            String js = "javascript:window.cyberSend(" + JSONObject.quote(prompt) + ");";
            webView.evaluateJavascript(js, null);
        });
    }

    private void initWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        // 伪装火狐 UA
        s.setUserAgentString("Mozilla/5.0 (Linux; Android 14; Mobile; rv:115.0) Gecko/115.0 Firefox/115.0");

        webView.addJavascriptInterface(new JsBridge(), "__bridge");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (url.contains("chat.deepseek.com")) {
                    injectHookJs(view);
                }
            }
        });

        webView.loadUrl("https://chat.deepseek.com");
    }

    private void injectHookJs(WebView view) {
        String js =
            "(function() {" +
            "  if (window.cyberHooked) { window.__bridge.onReady(); return; }" +
            "  window.cyberHooked = true;" +
            "  window.cyberSend = function(text) {" +
            "    let ta = document.querySelector('textarea');" +
            "    if (!ta) return;" +
            "    let setter = Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype, 'value').set;" +
            "    setter.call(ta, text);" +
            "    ta.dispatchEvent(new Event('input', { bubbles: true }));" +
            "    setTimeout(function() {" +
            "      ta.dispatchEvent(new KeyboardEvent('keydown', {key: 'Enter', code: 'Enter', keyCode: 13, bubbles: true}));" +
            "    }, 300);" +
            "  };" +
            "  let origFetch = window.fetch;" +
            "  window.fetch = async function() {" +
            "    let res = await origFetch.apply(this, arguments);" +
            "    let url = arguments[0];" +
            "    if (typeof url === 'string' && url.includes('/chat/completion')) {" +
            "      let clone = res.clone();" +
            "      let reader = clone.body.getReader();" +
            "      let dec = new TextDecoder();" +
            "      let go = () => reader.read().then(({done, value}) => {" +
            "        if (done) { window.__bridge.onFinish(); return; }" +
            "        let txt = dec.decode(value, {stream:true});" +
            "        window.__bridge.onChunk(txt);" +
            "        go();" +
            "      }); go();" +
            "    }" +
            "    return res;" +
            "  };" +
            "  window.__bridge.onReady();" +
            "})();";
        view.evaluateJavascript("javascript:" + js, null);
    }

    private class JsBridge {
        @JavascriptInterface
        public void onReady() {
            mainHandler.post(() -> {
                isPageHooked = true;
                tvStatus.setText("就绪");
            });
        }

        @JavascriptInterface
        public void onChunk(String data) {
            mainHandler.post(() -> handleSseChunk(data));
        }

        @JavascriptInterface
        public void onFinish() {
            mainHandler.post(() -> {
                flushUiUpdate();
                isRunning = false;
                tvStatus.setText("就绪");
            });
        }
    }

    private void handleSseChunk(String data) {
        sseBuffer.append(data);
        int idx;
        while ((idx = sseBuffer.indexOf("\n")) >= 0) {
            String line = sseBuffer.substring(0, idx).trim();
            sseBuffer.delete(0, idx + 1);
            if (!line.startsWith("data:")) continue;
            String payload = line.substring(5).trim();
            if ("[DONE]".equals(payload)) continue;
            try {
                JSONObject c = new JSONObject(payload);
                if (c.has("v") && c.get("v") instanceof String) {
                    pendingAiText.append(c.getString("v"));
                    scheduleUiUpdate();
                }
            } catch (Exception ignored) {}
        }
    }

    // 核心优化：每 50ms 合并一次 UI 刷新，防止打字太快卡顿
    private void scheduleUiUpdate() {
        if (!isUiUpdatePending) {
            isUiUpdatePending = true;
            mainHandler.postDelayed(this::flushUiUpdate, 50);
        }
    }

    private void flushUiUpdate() {
        if (pendingAiText.length() > 0) {
            String newText = pendingAiText.toString();
            pendingAiText.setLength(0);
            appendAiMsg(newText);
        }
        isUiUpdatePending = false;
    }

    private void appendAiMsg(String text) {
        if (msgList.isEmpty() || !"AI".equals(msgList.get(msgList.size() - 1).role)) {
            addMsg("AI", text);
        } else {
            Msg last = msgList.get(msgList.size() - 1);
            last.content += text;
            adapter.notifyItemChanged(msgList.size() - 1);
            recyclerView.scrollToPosition(msgList.size() - 1);
        }
    }

    private void addMsg(String role, String content) {
        msgList.add(new Msg(role, content));
        adapter.notifyItemInserted(msgList.size() - 1);
        recyclerView.scrollToPosition(msgList.size() - 1);
    }

    class MsgAdapter extends RecyclerView.Adapter<MsgAdapter.VH> {
        @NonNull @Override public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_message, parent, false));
        }
        @Override public void onBindViewHolder(@NonNull VH holder, int position) {
            Msg msg = msgList.get(position);
            holder.tvContent.setText(msg.content);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if ("User".equals(msg.role)) {
                params.gravity = android.view.Gravity.END;
                holder.tvContent.setBackgroundResource(R.drawable.bg_user_bubble);
                holder.tvContent.setTextColor(0xFFFFFFFF);
            } else {
                params.gravity = android.view.Gravity.START;
                holder.tvContent.setBackgroundResource(R.drawable.bg_ai_bubble);
                holder.tvContent.setTextColor(0xFF00E5FF);
            }
            holder.tvContent.setLayoutParams(params);
        }
        @Override public int getItemCount() { return msgList.size(); }
        class VH extends RecyclerView.ViewHolder { TextView tvContent; VH(View v) { super(v); tvContent = v.findViewById(R.id.tvContent); } }
    }
    static class Msg { String role, content; Msg(String role, String content) { this.role = role; this.content = content; } }
}
