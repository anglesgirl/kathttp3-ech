package dev.kathttp3.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Ao3TestScreen() } }
    }
}

@Composable
private fun Ao3TestScreen(vm: MainViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val scroll = rememberScrollState()

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(scroll),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("AO3 ECH / H3 直连测试", style = MaterialTheme.typography.headlineMedium)
        Text(
            "验证 AO3 能否用 ECH（加密 SNI）和 H3（HTTP/3）直连。\n" +
                "IP 写死、不解析域名；ECH 配置从 crypto.cloudflare.com 拉取（四家纯数字 DoH），绕过移动屏蔽。",
            style = MaterialTheme.typography.bodyMedium,
        )

        OutlinedTextField(
            value = state.ip,
            onValueChange = vm::setIp,
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.loading,
            label = { Text("目标 IP（写死，不做 DNS 解析）") },
            singleLine = true,
        )

        OutlinedTextField(
            value = state.host,
            onValueChange = vm::setHost,
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.loading,
            label = { Text("目标域名（SNI / 证书）") },
            singleLine = true,
        )

        OutlinedTextField(
            value = state.echDomain,
            onValueChange = vm::setEchDomain,
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.loading,
            label = { Text("ECH 配置来源域名") },
            supportingText = { Text("从该域名的 HTTPS DNS 记录提取 ECH（默认 crypto.cloudflare.com）") },
            singleLine = true,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = vm::runTest, enabled = !state.loading) {
                Text("开始测试")
            }
            OutlinedButton(onClick = vm::cancel, enabled = state.loading) { Text("取消") }
            TextButton(onClick = vm::clearLog, enabled = !state.loading) { Text("清空日志") }
        }

        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())

        Text("测试日志", style = MaterialTheme.typography.titleMedium)
        Card(modifier = Modifier.fillMaxWidth()) {
            SelectionContainer {
                Text(
                    state.log.ifEmpty { "（还没有运行测试）" },
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}
