package com.printbridge.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Button
import androidx.compose.material.Card
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.RadioButton
import androidx.compose.material.RadioButtonDefaults
import androidx.compose.material.Scaffold
import androidx.compose.material.Slider
import androidx.compose.material.SliderDefaults
import androidx.compose.material.Switch
import androidx.compose.material.SwitchDefaults
import androidx.compose.material.Text
import androidx.compose.material.TextFieldDefaults
import androidx.compose.material.TopAppBar
import androidx.compose.material.lightColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

private val Navy = Color(0xFF0B1F3D)
private val Amber = Color(0xFFFFB300)
private val Cream = Color(0xFFFFF0C2)
private val ScreenBg = Color(0xFFF1F3F6)
private val Muted = Color(0xFF5F6B7A)
private val Hairline = Color(0xFFC3C9D2)

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()

    private val btPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) vm.refresh() else vm.showStatus(getString(R.string.bt_permission_denied))
        }

    private val pickPdf =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) {
                startActivity(
                    Intent(this, PrintActivity::class.java)
                        .setAction(Intent.ACTION_VIEW)
                        .setDataAndType(uri, "application/pdf")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                )
            } else {
                vm.showStatus(getString(R.string.no_pdf))
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            btPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }
        setContent {
            PrintBridgeApp(
                vm = vm,
                onBluetoothSettings = {
                    try {
                        startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                    } catch (e: Exception) {
                        vm.showStatus(getString(R.string.error_fmt, e.message ?: ""))
                    }
                },
                onPickPdf = { pickPdf.launch(arrayOf("application/pdf")) },
                onEnablePrinting = {
                    try {
                        startActivity(Intent(Settings.ACTION_PRINT_SETTINGS))
                    } catch (e: Exception) {
                        vm.showStatus(getString(R.string.error_fmt, e.message ?: ""))
                    }
                },
            )
        }
    }

    override fun onResume() {
        super.onResume()
        vm.refresh()
    }
}

// =========================================================================
//  UI
// =========================================================================

@Composable
fun PrintBridgeApp(
    vm: MainViewModel,
    onBluetoothSettings: () -> Unit,
    onPickPdf: () -> Unit,
    onEnablePrinting: () -> Unit,
) {
    val ui by vm.state.collectAsState()
    val s = ui.settings
    val isReceipt = s.paper == PaperType.RECEIPT
    val hasPrinter = s.printerAddress.isNotBlank()

    MaterialTheme(
        colors = lightColors(
            primary = Navy,
            primaryVariant = Navy,
            secondary = Navy,
            background = ScreenBg,
            surface = Color.White,
        )
    ) {
        Scaffold(
            backgroundColor = ScreenBg,
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            stringResource(R.string.app_name),
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 20.sp,
                        )
                    },
                    backgroundColor = Navy,
                    contentColor = Color.White,
                    elevation = 0.dp,
                )
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // ---------------- Printer ----------------
                Section(stringResource(R.string.section_printer)) {
                    ui.printers.forEach { p ->
                        RadioRow(
                            selected = p.address == s.printerAddress && p.transport == s.transport,
                            title = p.name,
                            subtitle = p.detail,
                            onClick = { vm.select(p) },
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PillOutlined(stringResource(R.string.refresh)) { vm.refresh() }
                        PillOutlined(stringResource(R.string.bluetooth_settings), onBluetoothSettings)
                    }
                }

                // ---------------- Paper type ----------------
                Section(stringResource(R.string.section_paper)) {
                    RadioRow(
                        selected = s.paper == PaperType.RECEIPT,
                        title = stringResource(R.string.paper_receipt),
                        subtitle = stringResource(R.string.paper_receipt_desc),
                        onClick = { vm.update { copy(paper = PaperType.RECEIPT) } },
                    )
                    RadioRow(
                        selected = s.paper == PaperType.LABEL_GAP,
                        title = stringResource(R.string.paper_gap),
                        subtitle = stringResource(R.string.paper_gap_desc),
                        onClick = { vm.update { copy(paper = PaperType.LABEL_GAP) } },
                    )
                    RadioRow(
                        selected = s.paper == PaperType.LABEL_MARK,
                        title = stringResource(R.string.paper_mark),
                        subtitle = stringResource(R.string.paper_mark_desc),
                        onClick = { vm.update { copy(paper = PaperType.LABEL_MARK) } },
                    )
                }

                // ---------------- Roll size ----------------
                Section(stringResource(R.string.section_roll)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        IntField(
                            label = stringResource(R.string.width_mm),
                            value = s.widthMm,
                            onChange = { v -> vm.update { copy(widthMm = v) } },
                            modifier = Modifier.weight(1f),
                            isError = s.widthMm > MAX_WIDTH_MM,
                        )
                        if (isReceipt) {
                            IntField(
                                label = stringResource(R.string.page_mm),
                                value = s.pageMm,
                                onChange = { v -> vm.update { copy(pageMm = v) } },
                                modifier = Modifier.weight(1f),
                            )
                        } else {
                            IntField(
                                label = stringResource(R.string.height_mm),
                                value = s.heightMm,
                                onChange = { v -> vm.update { copy(heightMm = v) } },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    if (s.widthMm > MAX_WIDTH_MM) {
                        Text(
                            stringResource(R.string.max_width),
                            color = MaterialTheme.colors.error,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    if (!isReceipt) {
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (s.paper == PaperType.LABEL_GAP) {
                                IntField(
                                    label = stringResource(R.string.gap_mm),
                                    value = s.gapMm,
                                    onChange = { v -> vm.update { copy(gapMm = v) } },
                                    modifier = Modifier.weight(1f),
                                )
                            } else {
                                IntField(
                                    label = stringResource(R.string.mark_mm),
                                    value = s.markMm,
                                    onChange = { v -> vm.update { copy(markMm = v) } },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            Spacer(Modifier.weight(1f))
                        }
                    }
                    if (isReceipt) {
                        SwitchRow(
                            label = stringResource(R.string.trim_blank),
                            checked = s.trimBlank,
                            onChange = { v -> vm.update { copy(trimBlank = v) } },
                        )
                        Text(
                            stringResource(R.string.page_mm_hint),
                            fontSize = 11.sp,
                            color = Muted,
                        )
                    }
                }

                // ---------------- Print quality ----------------
                Section(stringResource(R.string.section_quality)) {
                    ValueSlider(
                        label = stringResource(R.string.darkness_fmt, s.darkness),
                        value = s.darkness,
                        min = 1,
                        max = 15,
                        steps = 13,
                        onChange = { v -> vm.update { copy(darkness = v) } },
                    )
                    ValueSlider(
                        label = stringResource(R.string.speed_fmt, s.speed),
                        value = s.speed,
                        min = 1,
                        max = 6,
                        steps = 4,
                        onChange = { v -> vm.update { copy(speed = v) } },
                    )
                    ValueSlider(
                        label = stringResource(R.string.threshold_fmt, s.threshold),
                        value = s.threshold,
                        min = 0,
                        max = 255,
                        steps = 0,
                        onChange = { v -> vm.update { copy(threshold = v) } },
                    )
                    SwitchRow(
                        label = stringResource(R.string.dithering),
                        checked = s.dither,
                        onChange = { v -> vm.update { copy(dither = v) } },
                    )

                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.orientation), fontSize = 14.sp, color = Muted)
                    RadioRow(
                        selected = s.orientation == Orientation.PORTRAIT,
                        title = stringResource(R.string.portrait),
                        subtitle = stringResource(R.string.portrait_desc),
                        onClick = { vm.update { copy(orientation = Orientation.PORTRAIT) } },
                    )
                    RadioRow(
                        selected = s.orientation == Orientation.LANDSCAPE,
                        title = stringResource(R.string.landscape),
                        subtitle = stringResource(R.string.landscape_desc),
                        onClick = { vm.update { copy(orientation = Orientation.LANDSCAPE) } },
                    )
                    RadioRow(
                        selected = s.orientation == Orientation.AUTO,
                        title = stringResource(R.string.auto),
                        subtitle = stringResource(R.string.auto_desc),
                        onClick = { vm.update { copy(orientation = Orientation.AUTO) } },
                    )

                    SwitchRow(
                        label = stringResource(R.string.rotate_180),
                        checked = s.rotate180,
                        onChange = { v -> vm.update { copy(rotate180 = v) } },
                    )
                    SwitchRow(
                        label = stringResource(R.string.invert_bits),
                        checked = s.invert,
                        onChange = { v -> vm.update { copy(invert = v) } },
                    )
                    Spacer(Modifier.height(8.dp))
                    IntField(
                        label = stringResource(R.string.offset_x),
                        value = s.offsetXDots,
                        onChange = { v -> vm.update { copy(offsetXDots = v) } },
                        modifier = Modifier.fillMaxWidth(),
                        allowNegative = true,
                    )
                }

                // ---------------- Actions ----------------
                Section(stringResource(R.string.section_actions)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PillFilled(
                            text = stringResource(R.string.test_print),
                            enabled = hasPrinter && !ui.busy,
                            onClick = { vm.testPrint() },
                        )
                        PillFilled(
                            text = stringResource(R.string.calibrate),
                            enabled = hasPrinter && !ui.busy && !isReceipt,
                            onClick = { vm.calibrate() },
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = onPickPdf,
                        shape = RoundedCornerShape(20.dp),
                        elevation = null,
                        colors = ButtonDefaults.buttonColors(
                            backgroundColor = Amber,
                            contentColor = Navy,
                        ),
                    ) {
                        Text(stringResource(R.string.print_pdf), fontSize = 14.sp)
                    }
                    Spacer(Modifier.height(8.dp))
                    PillOutlined(stringResource(R.string.enable_printing), onEnablePrinting)

                    if (ui.busy) {
                        Spacer(Modifier.height(12.dp))
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = Navy,
                        )
                    }
                    ui.status?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, fontSize = 13.sp, color = Muted)
                    }
                }
            }
        }
    }
}

// ----------------------------- building blocks -----------------------------

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        backgroundColor = Color.White,
        elevation = 1.dp,
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Color(0xFF111827))
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun RadioRow(
    selected: Boolean,
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            colors = RadioButtonDefaults.colors(
                selectedColor = Navy,
                unselectedColor = Color(0xFF3C4350),
            ),
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                title,
                fontSize = 16.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                color = Color(0xFF111827),
            )
            if (!subtitle.isNullOrEmpty()) {
                Text(subtitle, fontSize = 11.sp, color = Muted)
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 16.sp, color = Color(0xFF111827), modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Navy,
                checkedTrackColor = Navy,
                checkedTrackAlpha = 0.45f,
                uncheckedThumbColor = Color(0xFF6B7280),
                uncheckedTrackColor = Color(0xFF9CA3AF),
            ),
        )
    }
}

@Composable
private fun ValueSlider(
    label: String,
    value: Int,
    min: Int,
    max: Int,
    steps: Int,
    onChange: (Int) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Text(label, fontSize = 14.sp, color = Color(0xFF111827))
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.roundToInt()) },
            valueRange = min.toFloat()..max.toFloat(),
            steps = steps,
            colors = SliderDefaults.colors(
                thumbColor = Navy,
                activeTrackColor = Navy,
                inactiveTrackColor = Cream,
                activeTickColor = Cream,
                inactiveTickColor = Navy,
            ),
        )
    }
}

@Composable
private fun IntField(
    label: String,
    value: Int,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    allowNegative: Boolean = false,
) {
    var text by remember { mutableStateOf(value.toString()) }
    LaunchedEffect(value) {
        if (text.toIntOrNull() != value) text = value.toString()
    }
    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            val cleaned = raw.filterIndexed { i, c ->
                c.isDigit() || (allowNegative && i == 0 && c == '-')
            }.take(5)
            text = cleaned
            cleaned.toIntOrNull()?.let(onChange)
        },
        label = { Text(label) },
        singleLine = true,
        isError = isError,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (allowNegative) KeyboardType.Text else KeyboardType.Number
        ),
        colors = TextFieldDefaults.outlinedTextFieldColors(
            focusedBorderColor = Navy,
            focusedLabelColor = Navy,
            cursorColor = Navy,
        ),
        modifier = modifier,
    )
}

@Composable
private fun PillOutlined(text: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, Hairline),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Navy),
    ) {
        Text(text, fontSize = 14.sp)
    }
}

@Composable
private fun PillFilled(text: String, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(20.dp),
        elevation = null,
        colors = ButtonDefaults.buttonColors(
            backgroundColor = Navy,
            contentColor = Color.White,
            disabledBackgroundColor = Color(0xFFF1F2F4),
            disabledContentColor = Color(0xFF9AA3AF),
        ),
    ) {
        Text(text, fontSize = 14.sp)
    }
}
