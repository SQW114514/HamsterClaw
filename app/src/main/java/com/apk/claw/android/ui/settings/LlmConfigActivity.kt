package com.apk.claw.android.ui.settings

import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.widget.SwitchCompat
import com.apk.claw.android.ClawApplication
import com.apk.claw.android.R
import com.apk.claw.android.agent.AgentConfig
import com.apk.claw.android.agent.LlmProvider
import com.apk.claw.android.base.BaseActivity
import com.apk.claw.android.utils.KVUtils
import com.apk.claw.android.widget.CommonToolbar
import com.apk.claw.android.widget.KButton

class LlmConfigActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_llm_config)

        findViewById<CommonToolbar>(R.id.toolbar).apply {
            setTitle(getString(R.string.llm_config_title))
            showBackButton(true) { finish() }
        }

        val spProvider = findViewById<Spinner>(R.id.spProvider)
        val etApiKey = findViewById<EditText>(R.id.etApiKey)
        val etBaseUrl = findViewById<EditText>(R.id.etBaseUrl)
        val etModelName = findViewById<EditText>(R.id.etModelName)
        val layoutThinkingMode = findViewById<View>(R.id.layoutThinkingMode)
        val swThinkingMode = findViewById<SwitchCompat>(R.id.swThinkingMode)

        val providers = listOf(
            LlmProvider.OPENAI,
            LlmProvider.ANTHROPIC,
            LlmProvider.OPENGODE_GO,
            LlmProvider.DEEPSEEK
        )
        val providerLabels = providers.map { provider ->
            when (provider) {
                LlmProvider.OPENAI -> getString(R.string.llm_config_provider_openai)
                LlmProvider.ANTHROPIC -> getString(R.string.llm_config_provider_anthropic)
                LlmProvider.OPENGODE_GO -> getString(R.string.llm_config_provider_opencode_go)
                LlmProvider.DEEPSEEK -> getString(R.string.llm_config_provider_deepseek)
            }
        }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, providerLabels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spProvider.adapter = adapter

        val savedProviderStr = KVUtils.getLlmProvider()
        val savedProvider = try { LlmProvider.valueOf(savedProviderStr) } catch (e: Exception) { LlmProvider.OPENAI }
        val savedProviderIndex = providers.indexOf(savedProvider).coerceAtLeast(0)
        spProvider.setSelection(savedProviderIndex)

        etApiKey.setText(KVUtils.getLlmApiKey())
        etBaseUrl.setText(KVUtils.getLlmBaseUrl().ifEmpty { AgentConfig.defaultBaseUrl(savedProvider) })
        etModelName.setText(KVUtils.getLlmModelName())
        swThinkingMode.isChecked = KVUtils.isLlmThinkingMode()

        fun updateThinkingModeVisibility(provider: LlmProvider) {
            layoutThinkingMode.visibility = when (provider) {
                LlmProvider.DEEPSEEK, LlmProvider.OPENGODE_GO -> View.VISIBLE
                else -> View.GONE
            }
        }
        updateThinkingModeVisibility(savedProvider)

        spProvider.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedProvider = providers[position]
                val currentUrl = etBaseUrl.text.toString().trim()
                val currentProvider = try {
                    LlmProvider.valueOf(KVUtils.getLlmProvider())
                } catch (e: Exception) { LlmProvider.OPENAI }
                if (currentUrl.isEmpty() || currentUrl == AgentConfig.defaultBaseUrl(currentProvider)) {
                    etBaseUrl.setText(AgentConfig.defaultBaseUrl(selectedProvider))
                }
                updateThinkingModeVisibility(selectedProvider)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        findViewById<KButton>(R.id.btnSave).setOnClickListener {
            val apiKey = etApiKey.text.toString().trim()
            val baseUrl = etBaseUrl.text.toString().trim()
            val modelName = etModelName.text.toString().trim().ifEmpty { "" }
            val selectedProvider = providers[spProvider.selectedItemPosition]
            val thinkingMode = swThinkingMode.isChecked

            if (apiKey.isEmpty()) {
                Toast.makeText(this, getString(R.string.llm_config_api_key_required), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            KVUtils.setLlmProvider(selectedProvider.name)
            KVUtils.setLlmApiKey(apiKey)
            KVUtils.setLlmBaseUrl(baseUrl)
            KVUtils.setLlmModelName(modelName)
            KVUtils.setLlmThinkingMode(thinkingMode)

            ClawApplication.appViewModelInstance.updateAgentConfig()
            ClawApplication.appViewModelInstance.initAgent()
            ClawApplication.appViewModelInstance.afterInit()
            Toast.makeText(this, getString(R.string.llm_config_saved), Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
