<#
  首次运行配置向导（Windows）

  由 start.bat 在"还没建库 / 还没有 .env"时调用，弹一个窗口让用户只填四组参数：
  主力发言(chat) / 门控(gate) / 向量(embedding) / 重排(rerank) 各自的
  「接口地址 baseUrl」「密钥 API Key」「模型名 model」。

  保存时只做一件事：把值写进 .env（保留文件里其它行与注释，UTF-8 无 BOM）。
  之后的增删改都可以在「管理台 → 模型配置」里做（那边能热更新，不用重启）。

  退出码：0 = 已保存；2 = 用户取消。

  也支持无界面调用（给自动化测试用）：
      powershell -File setup-env.ps1 -Headless -ChatBaseUrl ... -ChatApiKey ... -ChatModel ...
#>
param(
    [string]$EnvPath = "",
    [switch]$Headless,
    [switch]$SelfTest,
    [switch]$DisableThinking,
    [int]$VecDimension = 0,
    [string]$ChatBaseUrl = "", [string]$ChatApiKey = "", [string]$ChatModel = "",
    [string]$GateBaseUrl = "", [string]$GateApiKey = "", [string]$GateModel = "",
    [string]$EmbeddingBaseUrl = "", [string]$EmbeddingApiKey = "", [string]$EmbeddingModel = "",
    [string]$RerankBaseUrl = "", [string]$RerankApiKey = "", [string]$RerankModel = ""
)

$ErrorActionPreference = "Stop"

if (-not $EnvPath -or $EnvPath -eq "") {
    # 与 DotenvEnvironmentPostProcessor 的查找一致：当前工作目录（start.bat 已 cd 到工程根目录）
    $EnvPath = Join-Path (Get-Location).Path ".env"
}

# ---------- .env 读写（只改受管行，其余原样保留） ----------

function Format-EnvValue([string]$v) {
    if ([string]::IsNullOrEmpty($v)) { return "" }
    $needQuote = $v.Contains("#") -or $v.Contains('"') -or $v.Contains("\") -or ($v -ne $v.Trim())
    if (-not $needQuote) { return $v }
    return '"' + $v.Replace("\", "\\").Replace('"', '\"') + '"'
}

function Write-EnvFile {
    param([hashtable]$Updates, [string]$Path)

    $lines = @()
    if (Test-Path -LiteralPath $Path) {
        $lines = @([System.IO.File]::ReadAllLines($Path))
    }

    foreach ($key in $Updates.Keys) {
        $line = "$key=$(Format-EnvValue ([string]$Updates[$key]))"
        $idx = -1
        for ($i = 0; $i -lt $lines.Count; $i++) {
            $t = ([string]$lines[$i]).Trim()
            if ($t.StartsWith("#")) { continue }
            if ($t.StartsWith("export ")) { $t = $t.Substring(7).Trim() }
            $eq = $t.IndexOf("=")
            if ($eq -gt 0 -and $t.Substring(0, $eq).Trim() -eq $key) { $idx = $i; break }
        }
        if ($idx -ge 0) { $lines[$idx] = $line } else { $lines = $lines + $line }
    }

    $dir = Split-Path -Parent $Path
    if ($dir -and -not (Test-Path -LiteralPath $dir)) {
        New-Item -ItemType Directory -Force -Path $dir | Out-Null
    }
    # UTF-8 无 BOM —— 带 BOM 会让第一个键名带上不可见字符，导致配置静默失效
    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllLines($Path, $lines, $utf8NoBom)
}

function Build-Updates {
    param([hashtable]$Values)
    $u = @{}
    foreach ($g in @("chat", "gate", "embedding", "rerank")) {
        $u["APP_MODEL_" + $g.ToUpper() + "_BASE_URL"] = $Values[$g + "_base"]
        $u["APP_MODEL_" + $g.ToUpper() + "_API_KEY"] = $Values[$g + "_key"]
        $u["APP_MODEL_" + $g.ToUpper() + "_MODEL"] = $Values[$g + "_model"]
        $u["APP_MODEL_" + $g.ToUpper() + "_DISABLE_THINKING"] =
            $(if ($Values["disableThinking"]) { "true" } else { "false" })
    }
    $u["APP_VEC_DIMENSION"] = [string]$Values["vecDim"]
    return $u
}

# ---------- 常用服务商预设（只填地址与模型名，Key 永远要用户自己粘） ----------
$Presets = @(
    @{ name = "阿里云百炼 / 通义千问（国内推荐）"; dim = 1024; thinking = $true;
       chat = @("https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus");
       gate = @("https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-flash");
       embedding = @("https://dashscope.aliyuncs.com/compatible-mode/v1", "text-embedding-v4");
       rerank = @("https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank", "gte-rerank-v2") },
    @{ name = "DeepSeek 官方"; dim = 1024; thinking = $false;
       chat = @("https://api.deepseek.com/v1", "deepseek-chat");
       gate = @("", ""); embedding = @("", ""); rerank = @("", "") },
    @{ name = "硅基流动 SiliconFlow"; dim = 1024; thinking = $false;
       chat = @("https://api.siliconflow.cn/v1", "deepseek-ai/DeepSeek-V3");
       gate = @("", "");
       embedding = @("https://api.siliconflow.cn/v1", "BAAI/bge-m3");
       rerank = @("https://api.siliconflow.cn/v1/rerank", "BAAI/bge-reranker-v2-m3") },
    @{ name = "智谱 GLM"; dim = 2048; thinking = $false;
       chat = @("https://open.bigmodel.cn/api/paas/v4", "glm-4-flash");
       gate = @("", "");
       embedding = @("https://open.bigmodel.cn/api/paas/v4", "embedding-3");
       rerank = @("https://open.bigmodel.cn/api/paas/v4/rerank", "rerank") },
    @{ name = "OpenAI"; dim = 1536; thinking = $false;
       chat = @("https://api.openai.com/v1", "gpt-4o-mini");
       gate = @("", "");
       embedding = @("https://api.openai.com/v1", "text-embedding-3-small");
       rerank = @("", "") },
    @{ name = "本地 Ollama（不需要 Key）"; dim = 768; thinking = $false;
       chat = @("http://localhost:11434/v1", "qwen2.5:7b");
       gate = @("", "");
       embedding = @("http://localhost:11434/v1", "nomic-embed-text");
       rerank = @("", "") }
)

# ---------- 无界面模式（自动化测试） ----------
if ($Headless) {
    $values = @{
        chat_base = $ChatBaseUrl; chat_key = $ChatApiKey; chat_model = $ChatModel
        gate_base = $GateBaseUrl; gate_key = $GateApiKey; gate_model = $GateModel
        embedding_base = $EmbeddingBaseUrl; embedding_key = $EmbeddingApiKey; embedding_model = $EmbeddingModel
        rerank_base = $RerankBaseUrl; rerank_key = $RerankApiKey; rerank_model = $RerankModel
        vecDim = $VecDimension
        disableThinking = [bool]$DisableThinking
    }
    Write-EnvFile -Updates (Build-Updates -Values $values) -Path $EnvPath
    Write-Output "saved: $EnvPath"
    exit 0
}

# ---------- 图形界面 ----------
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing

[System.Windows.Forms.Application]::EnableVisualStyles()

$fontTitle = New-Object System.Drawing.Font("Microsoft YaHei UI", 11, [System.Drawing.FontStyle]::Bold)
$fontBody = New-Object System.Drawing.Font("Microsoft YaHei UI", 9)
$fontDesc = New-Object System.Drawing.Font("Microsoft YaHei UI", 9)
$gray = [System.Drawing.Color]::FromArgb(120, 120, 120)

$form = New-Object System.Windows.Forms.Form
$form.Text = "首次运行配置 · AI 群聊"
$form.Size = New-Object System.Drawing.Size(820, 720)
$form.StartPosition = "CenterScreen"
$form.MinimizeBox = $false
$form.Font = $fontBody
$form.BackColor = [System.Drawing.Color]::White

# 顶部说明
$intro = New-Object System.Windows.Forms.Label
$intro.Text = "第一次运行，先把「大模型接口」填一下（就这一次，之后可以在「管理台 → 模型配置」里改，且改完立即生效）。`r`n不确定填什么？先在下面选一个常用服务商，地址和模型名会自动填好，你只要粘 API Key。"
$intro.Left = 14; $intro.Top = 10; $intro.Width = 780; $intro.Height = 52
$intro.Font = $fontBody
$form.Controls.Add($intro)

$presetLabel = New-Object System.Windows.Forms.Label
$presetLabel.Text = "常用服务商："
$presetLabel.Left = 14; $presetLabel.Top = 74; $presetLabel.Width = 84; $presetLabel.Height = 22
$form.Controls.Add($presetLabel)

$presetBox = New-Object System.Windows.Forms.ComboBox
$presetBox.Left = 100; $presetBox.Top = 71; $presetBox.Width = 400
$presetBox.DropDownStyle = [System.Windows.Forms.ComboBoxStyle]::DropDownList
$presetBox.Items.Add("（自己填 / 我不确定，先选一个）") | Out-Null
foreach ($p in $Presets) { $presetBox.Items.Add($p.name) | Out-Null }
$presetBox.SelectedIndex = 0
$form.Controls.Add($presetBox)

$scroll = New-Object System.Windows.Forms.Panel
$scroll.Left = 0; $scroll.Top = 102; $scroll.Width = 804; $scroll.Height = 520
$scroll.AutoScroll = $true
$form.Controls.Add($scroll)

function Add-Row {
    param($parent, [int]$y, [string]$label, [string]$name, [string]$value, [bool]$password)
    $l = New-Object System.Windows.Forms.Label
    $l.Text = $label; $l.Left = 10; $l.Top = $y + 3; $l.Width = 200; $l.Height = 22
    $parent.Controls.Add($l)
    $t = New-Object System.Windows.Forms.TextBox
    $t.Left = 214; $t.Top = $y; $t.Width = 560; $t.Name = $name; $t.Text = $value
    if ($password) { $t.UseSystemPasswordChar = $true }
    $parent.Controls.Add($t)
    return ($y + 30)
}

function Add-Group {
    param($parent, [int]$y, [string]$title, [string]$desc, [string]$key, [bool]$required)
    $h = New-Object System.Windows.Forms.Label
    $h.Text = $(if ($required) { "$title（必填）" } else { $title })
    $h.Left = 10; $h.Top = $y; $h.Width = 760; $h.Height = 24; $h.Font = $fontTitle
    if ($required) { $h.ForeColor = [System.Drawing.Color]::FromArgb(190, 60, 60) }
    $parent.Controls.Add($h)
    $y += 24
    $d = New-Object System.Windows.Forms.Label
    $d.Text = $desc; $d.Left = 10; $d.Top = $y; $d.Width = 760; $d.Height = 36
    $d.ForeColor = $gray
    $parent.Controls.Add($d)
    $y += 38

    $y = Add-Row $parent $y "接口地址 baseUrl" ("$key" + "_base") "" $false
    $y = Add-Row $parent $y "密钥 API Key" ("$key" + "_key") "" $true
    $y = Add-Row $parent $y "模型名 model" ("$key" + "_model") "" $false
    return ($y + 10)
}

$y = 6
$y = Add-Group $scroll $y "1. 主力发言模型" `
    "决定 agent「说什么」。每条正式发言都调用它，用质量好一点的模型。baseUrl 填到 /v1 为止，不要带 /chat/completions。" `
    "chat" $true
$y = Add-Group $scroll $y "2. 门控模型（可以留空）" `
    "决定每个 agent「要不要接话」，调用量最大的就是它，建议用最便宜的 flash 级小模型（qwen-flash、glm-4-flash）。留空 = 直接用上面的主力模型。" `
    "gate" $false
$y = Add-Group $scroll $y "3. 向量模型 embedding（可以留空）" `
    "把消息和记忆转成向量，长期记忆靠它检索。留空 = 记忆退化成「最近若干条」，不会报错。" `
    "embedding" $false
$y = Add-Group $scroll $y "4. 重排模型 rerank（可以留空）" `
    "对召回的候选精排。留空 = 直接按向量相似度排序，够用。注意这里要填完整的 rerank 端点，各家格式不一样。" `
    "rerank" $false

# 向量维度 + 进阶
$hdim = New-Object System.Windows.Forms.Label
$hdim.Text = "5. 向量维度"
$hdim.Left = 10; $hdim.Top = $y; $hdim.Width = 760; $hdim.Height = 24; $hdim.Font = $fontTitle
$scroll.Controls.Add($hdim)
$y += 24
$ddim = New-Object System.Windows.Forms.Label
$ddim.Text = "必须与「向量模型」的输出维度一致。常见：bge-m3 / text-embedding-v4 = 1024；text-embedding-3-small = 1536；智谱 embedding-3 = 2048；nomic-embed-text = 768。"
$ddim.Left = 10; $ddim.Top = $y; $ddim.Width = 760; $ddim.Height = 34; $ddim.ForeColor = $gray
$scroll.Controls.Add($ddim)
$y += 36
$y = Add-Row $scroll $y "向量维度" "vecDim" "1024" $false

$chk = New-Object System.Windows.Forms.CheckBox
$chk.Text = "关闭「思考模式」（请求体带 enable_thinking=false）"
$chk.Left = 214; $chk.Top = $y + 4; $chk.Width = 560; $chk.Height = 24; $chk.Name = "disableThinking"
$chk.Checked = $true
$scroll.Controls.Add($chk)
$y += 30
$hint = New-Object System.Windows.Forms.Label
$hint.Text = "阿里云百炼 / MaaS 等「思考模式」网关会拒绝工具调用（结构化输出会失败），保持勾选；DeepSeek 官方、OpenAI、硅基流动、智谱请取消勾选（它们不认这个字段）。"
$hint.Left = 10; $hint.Top = $y; $hint.Width = 760; $hint.Height = 40; $hint.ForeColor = $gray
$scroll.Controls.Add($hint)

# 底部按钮
$btnSave = New-Object System.Windows.Forms.Button
$btnSave.Text = "保存并启动"
$btnSave.Left = 520; $btnSave.Top = 636; $btnSave.Width = 120; $btnSave.Height = 34
$btnSave.BackColor = [System.Drawing.Color]::FromArgb(7, 193, 96)
$btnSave.ForeColor = [System.Drawing.Color]::White
$btnSave.FlatStyle = "Flat"
$form.Controls.Add($btnSave)

$btnCancel = New-Object System.Windows.Forms.Button
$btnCancel.Text = "取消"
$btnCancel.Left = 650; $btnCancel.Top = 636; $btnCancel.Width = 90; $btnCancel.Height = 34
$form.Controls.Add($btnCancel)

$envLabel = New-Object System.Windows.Forms.Label
$envLabel.Text = "保存到：" + $EnvPath
$envLabel.Left = 14; $envLabel.Top = 642; $envLabel.Width = 500; $envLabel.Height = 22
$envLabel.ForeColor = $gray
$form.Controls.Add($envLabel)

# 选服务商 = 自动填地址与模型（Key 不动）
$presetBox.Add_SelectedIndexChanged({
    if ($presetBox.SelectedIndex -le 0) { return }
    $p = $Presets[$presetBox.SelectedIndex - 1]
    foreach ($g in @("chat", "gate", "embedding", "rerank")) {
        $pair = $p[$g]
        $scroll.Controls["$($g)_base"].Text = $pair[0]
        $scroll.Controls["$($g)_model"].Text = $pair[1]
    }
    $scroll.Controls["vecDim"].Text = [string]$p.dim
    $scroll.Controls["disableThinking"].Checked = [bool]$p.thinking
})

$btnCancel.Add_Click({ $form.DialogResult = [System.Windows.Forms.DialogResult]::Cancel; $form.Close() })

$btnSave.Add_Click({
    $base = $scroll.Controls["chat_base"].Text.Trim()
    $key = $scroll.Controls["chat_key"].Text.Trim()
    $model = $scroll.Controls["chat_model"].Text.Trim()
    if (-not $base -or -not $key -or -not $model) {
        [System.Windows.Forms.MessageBox]::Show(
            "「主力发言模型」是必填的：接口地址、API Key、模型名三项都要填，Agent 才能说话。",
            "还差一点", "OK", "Warning") | Out-Null
        return
    }
    $dim = 0
    if (-not [int]::TryParse($scroll.Controls["vecDim"].Text.Trim(), [ref]$dim) -or $dim -le 0) {
        [System.Windows.Forms.MessageBox]::Show("「向量维度」请填正整数（不确定就保持 1024）。",
            "还差一点", "OK", "Warning") | Out-Null
        return
    }

    $values = @{
        chat_base = $base; chat_key = $key; chat_model = $model
        gate_base = $scroll.Controls["gate_base"].Text.Trim()
        gate_key = $scroll.Controls["gate_key"].Text.Trim()
        gate_model = $scroll.Controls["gate_model"].Text.Trim()
        embedding_base = $scroll.Controls["embedding_base"].Text.Trim()
        embedding_key = $scroll.Controls["embedding_key"].Text.Trim()
        embedding_model = $scroll.Controls["embedding_model"].Text.Trim()
        rerank_base = $scroll.Controls["rerank_base"].Text.Trim()
        rerank_key = $scroll.Controls["rerank_key"].Text.Trim()
        rerank_model = $scroll.Controls["rerank_model"].Text.Trim()
        vecDim = $dim
        disableThinking = [bool]$scroll.Controls["disableThinking"].Checked
    }
    try {
        Write-EnvFile -Updates (Build-Updates -Values $values) -Path $EnvPath
        $form.DialogResult = [System.Windows.Forms.DialogResult]::OK
        $form.Close()
    } catch {
        [System.Windows.Forms.MessageBox]::Show("写入失败：" + $_.Exception.Message, "出错了", "OK", "Error") | Out-Null
    }
})

# ---------- 自检（无界面验证控件与预设填充，给自动化用） ----------
if ($SelfTest) {
    $names = @("chat_base", "chat_key", "chat_model", "gate_base", "gate_key", "gate_model",
               "embedding_base", "embedding_key", "embedding_model",
               "rerank_base", "rerank_key", "rerank_model", "vecDim", "disableThinking")
    $missing = @($names | Where-Object { -not $scroll.Controls[$_] })
    if ($missing.Count) { Write-Output ("MISSING: " + ($missing -join ",")); exit 3 }

    $fail = @()
    for ($i = 1; $i -le $Presets.Count; $i++) {
        $presetBox.SelectedIndex = $i          # 触发 SelectedIndexChanged
        $p = $Presets[$i - 1]
        if ($scroll.Controls["chat_base"].Text -ne $p.chat[0]) { $fail += "preset#$i chat_base" }
        if ($scroll.Controls["chat_model"].Text -ne $p.chat[1]) { $fail += "preset#$i chat_model" }
        if ($scroll.Controls["vecDim"].Text -ne [string]$p.dim) { $fail += "preset#$i vecDim" }
        if ($scroll.Controls["disableThinking"].Checked -ne [bool]$p.thinking) { $fail += "preset#$i thinking" }
    }
    if ($fail.Count) { Write-Output ("FAIL: " + ($fail -join ",")); exit 4 }
    Write-Output ("selftest ok; controls=" + $scroll.Controls.Count + "; presets=" + $Presets.Count)
    exit 0
}

$null = $form.ShowDialog()
if ($form.DialogResult -eq [System.Windows.Forms.DialogResult]::OK) { exit 0 }
exit 2