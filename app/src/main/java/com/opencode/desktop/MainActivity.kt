package com.opencode.desktop

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.view.View
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.opencode.desktop.databinding.ActivityMainBinding
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var serverProcess: Process? = null
    private var serverThread: Thread? = null
    private var opencodeBinary: File? = null
    private lateinit var homeDir: File
    private lateinit var projectDir: File

    companion object {
        private const val TAG = "OpencodeDesktop"
        private const val PORT = 4096
        private const val URL_DESKTOP = "http://127.0.0.1:$PORT"
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupDirs()
        setupWebView()

        binding.swipeRefresh.setOnRefreshListener { binding.webView.reload() }
        binding.fabRefresh.setOnClickListener { binding.webView.reload() }

        // 1. Extrair binário (pode ser stub de 28 bytes se build falhou)
        try {
            updateLoading("Extraindo binário...", "Aguarde")
            opencodeBinary = BinaryManager.extractIfNeeded(this)
            val size = opencodeBinary!!.length()
            Log.i(TAG, "Binário pronto ${opencodeBinary!!.absolutePath} ${size/1024}KB")
            // Se for stub ( < 1KB ), não tenta iniciar servidor embutido — usa modo Termux
            if (size < 1024) {
                Log.w(TAG, "Binário stub detectado (size=$size), usando modo Termux")
                showTermuxMode()
                return
            }
        } catch (e: Exception) {
            Log.e(TAG, "Falha extrair", e)
            showTermuxMode()
            return
        }

        // 2. Iniciar servidor desktop (se binário válido)
        startServer()
    }

    private fun setupDirs() {
        homeDir = File(filesDir, "home").apply { mkdirs() }
        projectDir = File(homeDir, "project").apply { mkdirs() }
        File(homeDir, ".config/opencode").apply { mkdirs() }
        File(projectDir, "README.md").apply { if(!exists()) writeText("# Opencode Desktop Android\n\nProjeto inicial\n") }
        Log.i(TAG, "homeDir=${homeDir.absolutePath} projectDir=${projectDir.absolutePath}")
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        binding.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            allowFileAccessFromFileURLs = true
            allowUniversalAccessFromFileURLs = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            mixedContentMode = 0 // MIXED_CONTENT_ALWAYS_ALLOW
        }
        binding.webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                Log.i(TAG, "Page started $url")
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                Log.i(TAG, "Page finished $url")
                binding.loadingView.visibility = View.GONE
                binding.swipeRefresh.isRefreshing = false
                binding.fabRefresh.visibility = View.GONE
            }
            override fun onReceivedError(view: WebView?, req: WebResourceRequest?, err: WebResourceError?) {
                super.onReceivedError(view, req, err)
                Log.w(TAG, "WebView error $err")
                if (req?.isForMainFrame == true) {
                    // Mantém loading com retry
                    updateLoading("Aguardando servidor...", "Tentando reconectar em 2s")
                    view?.postDelayed({ checkAndLoad() }, 2000)
                }
            }
        }
    }

    private fun updateLoading(title: String, sub: String) {
        runOnUiThread {
            binding.loadingText.text = title
            binding.loadingSub.text = sub
            binding.loadingView.visibility = View.VISIBLE
        }
    }

    private fun startServer() {
        val bin = opencodeBinary ?: return
        updateLoading("Iniciando servidor Desktop...", "Porta $PORT")
        // Env essencial
        val env = arrayOf(
            "HOME=${homeDir.absolutePath}",
            "TMPDIR=${cacheDir.absolutePath}",
            "PATH=${bin.parent}:${homeDir.absolutePath}/bin:/system/bin:/system/xbin",
            "TERM=xterm-256color",
            "LANG=en_US.UTF-8",
            "LC_ALL=en_US.UTF-8"
        )
        // Args: serve headless na porta local. 'opencode serve' ou 'opencode web' — serve é headless, web abre browser (desnecessário)
        // Testado: opencode serve --port 4096 --hostname 127.0.0.1
        val cmd = arrayOf(bin.absolutePath, "serve", "--port", PORT.toString(), "--hostname", "127.0.0.1")
        // Fallback: se serve falhar, tenta 'web' ou sem args (TUI não serve)
        Log.i(TAG, "Exec: ${cmd.joinToString(" ")} cwd=${projectDir.absolutePath}")

        serverThread = Thread {
            try {
                val pb = ProcessBuilder(*cmd)
                    .directory(projectDir)
                    .redirectErrorStream(true)

                // Aplica env (mantém env base + custom)
                val envMap = pb.environment()
                for (e in env) {
                    val kv = e.split("=", limit=2)
                    if (kv.size==2) envMap[kv[0]] = kv[1]
                }
                Log.i(TAG, "ENV: $envMap")

                serverProcess = pb.start()
                Log.i(TAG, "Server PID iniciado")

                // Loga stdout/stderr do servidor
                val reader = serverProcess!!.inputStream.bufferedReader()
                Thread {
                    try {
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            Log.i(TAG, "[opencode-serve] $line")
                            // Quando ver "listening" ou "server" -> servidor pronto
                            if (line!!.contains("listening", ignoreCase=true) || line!!.contains("http://127.0.0.1:$PORT", ignoreCase=true) || line!!.contains("started", ignoreCase=true)) {
                                runOnUiThread { checkAndLoad() }
                            }
                        }
                    } catch (e: Exception) { Log.w(TAG, "reader fail ${e.message}") }
                }.start()

                // Poll ativo até servidor responder HTTP 200
                pollServerReady()

                val exit = serverProcess!!.waitFor()
                Log.i(TAG, "Server exit $exit")
                if (exit != 0) {
                    runOnUiThread {
                        updateLoading("Servidor encerrou (exit $exit)", "Toque para reiniciar")
                        binding.loadingView.setOnClickListener { startServer() }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Falha startServer", e)
                runOnUiThread {
                    updateLoading("Falha ao iniciar", e.message ?: "erro")
                    Toast.makeText(this, "Falha servidor: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.also { it.start() }

        // Também inicia poll imediatamente (não espera log)
        Thread { pollServerReady() }.start()
    }

    private fun showTermuxMode() {
        updateLoading("Modo Termux", "Inicie o servidor no Termux")
        binding.loadingSub.text = "Abra o Termux e rode:\nopencode serve --port 4096 --hostname 127.0.0.1\n\nDepois arraste para atualizar"
        binding.loadingView.setOnClickListener { checkAndLoad() }
        binding.fabRefresh.visibility = View.VISIBLE
        binding.fabRefresh.setOnClickListener { checkAndLoad() }
        // Poll infinito: quando servidor aparecer (via Termux), carrega
        Thread {
            pollServerReadyInfinite()
        }.start()
        // Tenta carregar de 3 em 3s
        binding.webView.postDelayed({ pollServerReadyInfinite() }, 3000)
    }

    private fun pollServerReadyInfinite() {
        for (i in 1..999) {
            try {
                Thread.sleep(1500)
                val url = URL(URL_DESKTOP)
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 1000
                conn.readTimeout = 1000
                conn.requestMethod = "GET"
                val code = conn.responseCode
                Log.i(TAG, "PollTermux $i -> $code")
                if (code in 200..399) {
                    runOnUiThread { loadDesktop() }
                    return
                }
            } catch (e: Exception) { Log.d(TAG, "PollTermux $i fail ${e.message}") }
            if (i % 5 == 0) runOnUiThread { binding.loadingSub.text = "Aguardando Termux... tentativa $i\nopencode serve --port 4096" }
        }
    }

    private fun pollServerReady() {
        // Tenta por 30s fazer GET em http://127.0.0.1:4096
        for (i in 1..30) {
            try {
                Thread.sleep(1000)
                val url = URL(URL_DESKTOP)
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 1000
                conn.readTimeout = 1000
                conn.requestMethod = "GET"
                val code = conn.responseCode
                Log.i(TAG, "Poll $i -> $code")
                if (code in 200..399) {
                    runOnUiThread { loadDesktop() }
                    return
                }
            } catch (e: Exception) {
                Log.d(TAG, "Poll $i fail ${e.message}")
            }
            runOnUiThread { updateLoading("Iniciando servidor Desktop...", "Tentativa $i/30") }
        }
        // Se não conseguiu após 30s, cai para modo Termux
        runOnUiThread { showTermuxMode() }
    }

    private fun checkAndLoad() {
        // Verifica se já carregou
        if (binding.loadingView.visibility == View.GONE) return
        loadDesktop()
    }

    private fun loadDesktop() {
        runOnUiThread {
            Log.i(TAG, "Carregando $URL_DESKTOP no WebView")
            updateLoading("Carregando Desktop...", URL_DESKTOP)
            binding.webView.loadUrl(URL_DESKTOP)
            // Fallback: se em 5s não carregou, mantém loading mas permite swipe
            binding.webView.postDelayed({
                if (binding.loadingView.visibility == View.VISIBLE) {
                    binding.loadingSub.text = "Se a página não carregar, arraste para atualizar"
                    binding.fabRefresh.visibility = View.VISIBLE
                }
            }, 5000)
        }
    }

    override fun onBackPressed() {
        if (binding.webView.canGoBack()) binding.webView.goBack()
        else super.onBackPressed()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            serverProcess?.destroy()
            serverProcess?.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
            serverProcess?.destroyForcibly()
        } catch (e: Exception) { Log.w(TAG, "destroy fail ${e.message}") }
        serverThread?.interrupt()
        binding.webView.destroy()
    }
}
