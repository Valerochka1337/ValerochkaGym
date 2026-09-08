package com.valerochka1337.valerochkagym.ui.account

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private fun Context.activity(): Activity? =
    when (this) {
      is Activity -> this
      is ContextWrapper -> baseContext.activity()
      else -> null
    }

@Composable
fun AccountGate(content: @Composable () -> Unit) {
  val vm: AccountViewModel = hiltViewModel()
  val session by vm.session.collectAsStateWithLifecycle()
  val context = LocalContext.current
  var resetLocal by remember { mutableStateOf(false) }
  val export =
      androidx.activity.compose.rememberLauncherForActivityResult(
          androidx.activity.result.contract.ActivityResultContracts.CreateDocument(
              "application/octet-stream"
          )
      ) { uri ->
        uri?.let(vm::exportLocal)
      }
  if (session != null) content()
  else
      Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize()
                .safeDrawingPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp)
        ) {
          Text("ValerochkaGym", style = MaterialTheme.typography.headlineLarge)
          Text("Тренировки и прогресс в вашем аккаунте", style = MaterialTheme.typography.bodyLarge)
          Spacer(Modifier.height(24.dp))
          AccountForm(vm)
          TextButton(onClick = { export.launch("valerochka-gym-backup.db") }) {
            Text("Экспортировать локальную базу")
          }
          TextButton(onClick = { resetLocal = true }) {
            Text("Очистить устройство для другого аккаунта")
          }
        }
      }
  if (resetLocal)
      AlertDialog(
          onDismissRequest = { resetLocal = false },
          title = { Text("Очистить все данные приложения на устройстве?") },
          text = {
            Text(
                "Будут удалены локальные тренировки, настройки и ключи интеграций. Приложение закроется. Серверные данные сохранятся. Сначала экспортируйте несинхронизированную историю."
            )
          },
          confirmButton = {
            TextButton(
                onClick = {
                  resetLocal = false
                  (context.getSystemService(Context.ACTIVITY_SERVICE)
                          as android.app.ActivityManager)
                      .clearApplicationUserData()
                }
            ) {
              Text("Очистить устройство")
            }
          },
          dismissButton = { TextButton(onClick = { resetLocal = false }) { Text("Отмена") } },
      )
}

@Composable
private fun AccountForm(vm: AccountViewModel) {
  val busy by vm.busy.collectAsStateWithLifecycle()
  val message by vm.message.collectAsStateWithLifecycle()
  var mode by remember { mutableStateOf("login") }
  var email by remember { mutableStateOf("") }
  var password by remember { mutableStateOf("") }
  var code by remember { mutableStateOf("") }
  var adopt by remember { mutableStateOf(false) }
  val activity = LocalContext.current.activity()
  val title =
      when (mode) {
        "register" -> "Создать аккаунт"
        "verify" -> "Подтвердить email"
        "reset" -> "Новый пароль"
        "request-reset" -> "Восстановить пароль"
        else -> "Войти"
      }
  Column(
      Modifier.widthIn(max = 600.dp).fillMaxWidth(),
      verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Text(title, style = MaterialTheme.typography.titleLarge)
    OutlinedTextField(
        email,
        { email = it },
        Modifier.fillMaxWidth(),
        enabled = !busy,
        label = { Text("Email") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
    )
    if (mode in setOf("login", "register", "reset"))
        OutlinedTextField(
            password,
            { password = it },
            Modifier.fillMaxWidth(),
            enabled = !busy,
            label = { Text("Пароль") },
            supportingText = { if (mode != "login") Text("От 12 до 128 символов") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
    if (mode in setOf("verify", "reset"))
        OutlinedTextField(
            code,
            { code = it.filter(Char::isDigit).take(8) },
            Modifier.fillMaxWidth(),
            enabled = !busy,
            label = { Text("Код из письма") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        )
    if (mode == "login")
        Row {
          Checkbox(adopt, { adopt = it }, enabled = !busy)
          Text(
              "Сохранить мои локальные тренировки в этом аккаунте",
              Modifier.weight(1f).padding(top = 12.dp),
          )
        }
    message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    Button(
        onClick = { vm.submit(mode, email, password, code) },
        enabled = !busy && email.isNotBlank() && (mode != "login" || adopt),
        modifier = Modifier.fillMaxWidth(),
    ) {
      Text(title)
    }
    if (mode == "login") {
      OutlinedButton(
          onClick = { activity?.let { vm.google(it) } },
          enabled = !busy && adopt && activity != null,
          modifier = Modifier.fillMaxWidth(),
      ) {
        Text("Войти через Google")
      }
      TextButton(onClick = { mode = "register" }, enabled = !busy) { Text("Создать аккаунт") }
      TextButton(onClick = { mode = "request-reset" }, enabled = !busy) { Text("Забыли пароль?") }
      TextButton(onClick = { mode = "verify" }, enabled = !busy) {
        Text("Ввести код подтверждения email")
      }
    } else {
      if (mode == "register")
          TextButton(onClick = { mode = "verify" }, enabled = !busy) {
            Text("У меня есть код подтверждения")
          }
      if (mode == "verify")
          TextButton(onClick = { vm.submit("resend", email, "", "") }, enabled = !busy) {
            Text("Отправить код повторно")
          }
      if (mode == "request-reset")
          TextButton(onClick = { mode = "reset" }, enabled = !busy) {
            Text("Ввести код и новый пароль")
          }
      TextButton(
          onClick = {
            mode = "login"
            password = ""
            code = ""
          },
          enabled = !busy,
      ) {
        Text("Вернуться ко входу")
      }
    }
  }
}

@Composable
fun AccountCard(vm: AccountViewModel = hiltViewModel()) {
  val session by vm.session.collectAsStateWithLifecycle()
  val status by vm.status.collectAsStateWithLifecycle()
  val conflict by vm.conflict.collectAsStateWithLifecycle()
  val catalogConflict by vm.catalogConflict.collectAsStateWithLifecycle()
  val busy by vm.busy.collectAsStateWithLifecycle()
  val message by vm.message.collectAsStateWithLifecycle()
  val sessions by vm.sessions.collectAsStateWithLifecycle()
  var confirm by remember { mutableStateOf<String?>(null) }
  var deleteCode by remember { mutableStateOf("") }
  var deleting by remember { mutableStateOf(false) }
  val activity = LocalContext.current.activity()
  Card(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text("Аккаунт ValerochkaGym", style = MaterialTheme.typography.titleLarge)
      Text(session?.email ?: "Вход не выполнен")
      Text(status, style = MaterialTheme.typography.bodyMedium)
      message?.let { Text(it) }
      if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
      Button(onClick = { vm.synchronize() }, enabled = !busy) { Text("Синхронизировать") }
      if (conflict) {
        Text(
            if (catalogConflict)
                "Упражнения и залы стали стандартными. Ваши локальные правки и исходный пакет сохранены до выбора."
            else
                "Одни и те же данные изменились на двух устройствах. Выбранная версия заменит конфликтующие записи."
        )
        OutlinedButton(onClick = { confirm = "local" }, enabled = !busy) {
          Text(
              if (catalogConflict) "Сохранить правки личными копиями"
              else "Оставить изменения этого устройства"
          )
        }
        OutlinedButton(onClick = { confirm = "server" }, enabled = !busy) {
          Text(if (catalogConflict) "Принять стандартные версии" else "Принять изменения с сервера")
        }
      }
      TextButton(onClick = { activity?.let { vm.google(it, true) } }, enabled = !busy) {
        Text("Подключить вход через Google")
      }
      TextButton(onClick = vm::loadSessions, enabled = !busy) { Text("Устройства и сессии") }
      sessions.forEach { s ->
        Row(Modifier.fillMaxWidth()) {
          Text(s.deviceName + if (s.current) " · это устройство" else "", Modifier.weight(1f))
          if (!s.current)
              TextButton(onClick = { vm.revoke(s.id) }, enabled = !busy) { Text("Завершить") }
        }
      }
      TextButton(onClick = { confirm = "logout" }, enabled = !busy) { Text("Выйти") }
      TextButton(onClick = { confirm = "logout-all" }, enabled = !busy) {
        Text("Выйти на всех устройствах")
      }
      TextButton(onClick = { deleting = !deleting }, enabled = !busy) { Text("Удалить аккаунт") }
      if (deleting) {
        Text(
            "Удаление навсегда удалит данные аккаунта с сервера. Перед удалением можно экспортировать локальную базу в разделе «Данные и приложение»."
        )
        TextButton(onClick = vm::deletionCode, enabled = !busy) { Text("Получить код удаления") }
        OutlinedTextField(
            deleteCode,
            { deleteCode = it.filter(Char::isDigit).take(8) },
            label = { Text("Код удаления") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        )
        TextButton(onClick = { confirm = "delete" }, enabled = !busy && deleteCode.length == 8) {
          Text("Удалить навсегда")
        }
      }
    }
  }
  confirm?.let { action ->
    AlertDialog(
        onDismissRequest = { confirm = null },
        title = {
          Text(if (action == "delete") "Удалить аккаунт навсегда?" else "Подтвердить действие?")
        },
        text = {
          Text(
              when (action) {
                "local" ->
                    if (catalogConflict)
                        "Будут созданы личные копии ваших правок с новыми UUID. История останется связана со стандартными объектами."
                    else
                        "Конфликтующие записи на сервере будут заменены изменениями этого устройства."
                "server" ->
                    if (catalogConflict)
                        "Локальные правки перенесённых объектов будут заменены стандартными версиями. Личные тренировки сохранятся."
                    else
                        "Конфликтующие локальные записи будут заменены серверными. При необходимости сначала экспортируйте локальную базу."
                "delete" -> "Восстановить серверные данные после удаления будет невозможно."
                else ->
                    "Локальные данные останутся привязаны к этому аккаунту. Для продолжения потребуется снова войти."
              }
          )
        },
        confirmButton = {
          TextButton(
              onClick = {
                confirm = null
                when (action) {
                  "local",
                  "server" -> vm.synchronize(action)
                  "delete" -> vm.delete(deleteCode)
                  else -> vm.logout(action == "logout-all")
                }
              }
          ) {
            Text("Подтвердить")
          }
        },
        dismissButton = { TextButton(onClick = { confirm = null }) { Text("Отмена") } },
    )
  }
}
