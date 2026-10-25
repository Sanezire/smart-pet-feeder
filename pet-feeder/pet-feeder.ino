#include <ESP8266WiFi.h>
#include <ESP8266WebServer.h>
#include <ESP8266HTTPClient.h>
#include <ESP8266mDNS.h>
#include <DNSServer.h>
#include <EEPROM.h>
#include <ArduinoJson.h>
#include <Servo.h>
#include <time.h>
#include <sys/time.h>
#include <WiFiClientSecureBearSSL.h>
#include "HX711.h"

#define PIN_SERVO         D3   // GPIO14 - no boot-strap behaviour
#define PIN_TRAY          D8
#define PIN_RELAY         D1   // GPIO5 - water valve / pump relay
#define PIN_FLOW_SENSOR   D2   // GPIO4 - YF-S201 pulse output
#define PIN_FOOD_SENSOR   A0

// ---- Water dispensing (YF-S201 flow sensor + relay) ----
#define FLOW_CALIBRATION_FACTOR   11.5f   // YF-S201: pulse frequency (Hz) = 7.5 * flow (L/min)
#define WATER_MAX_LITERS          5.0f      // hard cap for a single dispense
#define WATER_REPORT_INTERVAL_MS  1000UL
#define WATER_NO_FLOW_TIMEOUT_MS  15000UL   // dry-run protection: stop if no pulses for this long
#define RELAY_ON_LEVEL            LOW    // active-low relay module (use HIGH for active-high)
#define RELAY_OFF_LEVEL           (RELAY_ON_LEVEL == HIGH ? LOW : HIGH)
// pulses per liter = (7.5 Hz per L/min) * 60 s = 450
#define WATER_PULSES_PER_LITER    (FLOW_CALIBRATION_FACTOR * 60.0f)
#define WATER_PULSE_MIN_US        5000UL
#define WATER_SETTLE_MS           600UL

#define HX711_DOUT 12
#define HX711_SCK  13

#define CALIBRATION_FACTOR 407.9

#define SAMPLES 10

#define ZERO_THRESHOLD 0.5

#define WEIGHT_PRINT_INTERVAL_MS 500UL
#define WEIGHT_TIMEOUT_MS        5000UL

#define AP_SSID           "PetFeeder_Setup"
#define AP_PASSWORD       ""
#define AP_CHANNEL        6
#define AP_HIDDEN         false
#define AP_MAX_CLIENTS    4
#define AP_IP_ADDR        "192.168.4.1"
#define FIREBASE_DB_URL   "https://smart-ped-feeder-default-rtdb.firebaseio.com"
#define FIREBASE_AUTH_TOKEN ""

#define EEPROM_SIZE       512
#define EEPROM_MAGIC      0xAB
#define EEPROM_MAGIC_ADDR 0
#define EEPROM_SSID_ADDR  1
#define EEPROM_SSID_LEN   64
#define EEPROM_PASS_ADDR  66
#define EEPROM_PASS_LEN   128
#define EEPROM_SCHED_MAGIC_ADDR 195
#define EEPROM_SCHED_COUNT_ADDR 196
#define EEPROM_SCHED_ADDR       197
#define EEPROM_SCHED_MAGIC      0x5C
#define MAX_SCHEDULES           16
#define SCHEDULE_RECORD_SIZE    8
#define EEPROM_UID_MAGIC_ADDR   340
#define EEPROM_UID_ADDR         341
#define EEPROM_UID_LEN          48
#define EEPROM_UID_MAGIC        0xA7
#define EEPROM_PORTION_ADDR     400
#define EEPROM_DEF_PORTION_ADDR 440

#define WIFI_CONNECT_TIMEOUT_MS   30000UL
#define WIFI_RECONNECT_TIMEOUT_MS 30000UL
#define SERVO_HOME_ANGLE          0
#define SERVO_SWEEP_ANGLE         90
#define SERVO_HOLD_MS             2000UL
#define TRAY_NEUTRAL_US           1500   // [CALIBRATE] stop command (microseconds)
#define TRAY_OPEN_US              1700   // [CALIBRATE] opening direction / speed
#define TRAY_CLOSE_US             1300
#define TRAY_OPEN_MS              15000UL // [CALIBRATE] time spent turning in the opening direction
#define TRAY_AUTO_CLOSE           0
#define TRAY_HOLD_MS              5000UL
#define TRAY_CLOSE_MS             15000UL
#define TRAY_SETTLE_MS            400UL
#define TRAY_COOLDOWN_MS          2000UL
#define TRAY_OK                   0
#define TRAY_ERR_BUSY             1
#define TRAY_ERR_COOLDOWN         2
#define TRAY_ERR_FEEDING          3
#define TRAY_ERR_NOT_OPEN         4
#define TRAY_IDLE                 0      // closed, servo detached
#define TRAY_OPENING              1      // timed turn in the opening direction
#define TRAY_HOLDING              2      // open, neutral command, servo still attached
#define TRAY_CLOSING              3      // timed turn back to the closed position
#define TRAY_STOPPING             4      // neutral command settling, detach pending
#define PROVISION_TOKEN_TTL_MS    120000UL
#define SCHEDULE_CHECK_MS         1000UL
#define NTP_SYNC_INTERVAL_MS      3600000UL
#define TZ_OFFSET_SECONDS         28800L
#define FIREBASE_POLL_MS          5000UL
#define REMOTE_COMMAND_EXPIRY_MS  600000UL
#define FIREBASE_HEARTBEAT_MS     15000UL
#define LIVE_WEIGHT_PUSH_MS       3000UL
#define LIVE_WEIGHT_MAX_AGE_MS    10000UL
#define WIFI_RETRY_INITIAL_MS     2000UL
#define WIFI_RETRY_MAX_MS         30000UL
#define FIREBASE_HTTP_TIMEOUT_MS  5000
#define FEED_SETTLE_MS            1500UL
#define PORTION_LOW_RATIO         0.80f
#define PORTION_HIGH_RATIO        1.20f
#define MAX_PENDING_RECORDS       8
#define EAT_WINDOW_MS             1800000UL
#define EAT_RETRY_MS              30000UL
#define EAT_MAX_RETRIES           10
#define DISPENSE_MAX_MS           15000UL
#define DISPENSE_MIN_NEED_GRAMS   5.0f
#define DISPENSE_LOG_MS           1000UL
#define EAT_LIVE_PATCH_MS         60000UL
#define WEIGHT_PUSH_DELTA_G       1.0f
#define SCALE_RETRY_MS            10000UL
#define STABLE_SPREAD_G           1.0f
#define BASELINE_STABLE_MAX_MS    8000UL
#define EAT_SAMPLE_MS             1000UL
#define EAT_NOISE_G               1.5f
#define EAT_REFILL_G              3.0f
#define EAT_BOWL_REMOVED_G        3.0f
#define EAT_DISTURB_DROP_G        20.0f

enum OperatingMode {
  MODE_AP_ONLY,
  MODE_CONNECTING,
  MODE_STATION
};

ESP8266WebServer server(80);
DNSServer        dnsServer;
bool             dnsRunning = false;
Servo            feederServo;
Servo            trayServo;
HX711            scale;
WiFiEventHandler stationDisconnectedHandler;
WiFiEventHandler stationGotIpHandler;

OperatingMode currentMode         = MODE_AP_ONLY;
String        savedSSID           = "";
String        savedPassword       = "";
bool          isFeeding           = false;
unsigned long feedingStartMs      = 0;
unsigned long connectStartMs      = 0;
String        provisionToken      = "";
unsigned long provisionTokenExpiryMs = 0;
bool          finalizeStationSwitchPending = false;
unsigned long finalizeStationSwitchAtMs = 0;
unsigned long lastScheduleCheckMs = 0;
unsigned long lastNtpSyncAttemptMs = 0;
unsigned long lastFirebasePollMs = 0;
unsigned long nextWifiRetryMs = 0;
unsigned long wifiRetryBackoffMs = WIFI_RETRY_INITIAL_MS;
bool          stationConnectInProgress = false;
String        pendingProvisionSsid = "";
String        pendingProvisionPass = "";
String        provisioningError = "";
int           lastDisconnectReason = -1;
uint32_t lastRemoteConfigHash = 0;
String lastProcessedRemoteCommandId = "";
uint8_t       trayState           = TRAY_IDLE;
unsigned long trayPhaseStartMs    = 0;
unsigned long trayLastEndMs       = 0;
bool          trayHasRun          = false;
String        trayRemoteCommandId = "";   // Firebase command to acknowledge when the cycle ends
String        trayLastCommandId   = "";
unsigned long trayOpenedMs        = 0;
unsigned long trayCloseDurationMs = 0;
int currentLocalHour = -1;
int currentLocalMinute = -1;
int currentLocalWday = -1;

bool          scaleAvailable      = false;
float         currentWeightGrams  = 0.0;
float         weightSamples[SAMPLES];
int           weightSampleCount   = 0;
int           weightSampleIndex   = 0;
unsigned long lastWeightSampleMs  = 0;
unsigned long lastWeightPrintMs   = 0;
int           missedWeightSamples = 0;
float         fastWeightGrams     = 0.0;

String        deviceUid           = "";
float         defaultPortionGrams = 0.0;
unsigned long lastHeartbeatMs     = 0;
float         lastPushedWeight    = -1000.0f;
unsigned long lastLiveWeightPushMs = 0;
unsigned long lastScaleRetryMs    = 0;
bool          weightSkipLogged    = false;
long          lastAckedVersion    = 0;

float         feedTargetGrams     = 0.0;
float         feedStartWeight     = 0.0;
bool          feedIsScheduled     = false;
uint32_t      feedScheduleHash    = 0;
uint32_t      feedSlotEpochSec    = 0;
uint32_t      feedStartEpochSec   = 0;
String        feedDateStr         = "";
bool          feedFinalizePending = false;
unsigned long feedFinalizeAtMs    = 0;
float         feedMeasuredWeight  = 0.0;
bool          feedWeightMode      = false;
float         feedNeedGrams       = 0.0;
float         feedStopAt          = 0.0;
unsigned long feedLastLogMs       = 0;
bool          feedBowlFull        = false;
bool          feedTimedOut        = false;
String        feedRecordId        = "";
String        feedManualRecordId  = "";

struct EatCheck {
  bool          active;
  String        id;
  float         afterDispense;
  unsigned long dueMs;
  int           retries;
  unsigned long nextLiveMs;
  float         refWeight;
  float         consumed;
  float         lastRemaining;
  bool          bowlRemoved;
  unsigned long nextSampleMs;
  float         target;
};
EatCheck eatCheck = { false, "", 0.0f, 0, 0, 0, 0.0f, 0.0f, 0.0f, false, 0, 0.0f };

bool          lastFinalStatsValid = false;
float         lastFinalRemaining  = 0.0f;

bool          liveFeedDirty            = false;
bool          liveFeedEndPending       = false;
float         liveFeedSentRemaining    = -1000.0f;
float         liveFeedSentConsumed     = -1000.0f;
bool          liveFeedSentBowlRemoved  = false;
String liveRemainingId   = "";
String liveRemainingJson = "";

struct PendingRecord { String id; String json; };
PendingRecord pendingRecords[MAX_PENDING_RECORDS];
int           pendingRecordCount  = 0;

struct LocalSchedule {
  uint32_t idHash;
  uint8_t hour;
  uint8_t minute;
  bool enabled;
  uint8_t daysMask;
  int lastTriggeredYday;
  float portionGrams;
};

LocalSchedule localSchedules[MAX_SCHEDULES];
int localScheduleCount = 0;

bool hasEnabledSchedule() {
  for (int i = 0; i < localScheduleCount; i++) {
    if (localSchedules[i].enabled) return true;
  }
  return false;
}

bool isClockSynced();
void syncClockIfNeeded(bool force);
void updateScheduledFeeding();
void updateFirebaseRemoteControl();
void markStationConnected();
uint8_t repeatDaysToMask(JsonArray days);
void triggerFeed(float portionGrams = 0.0f, bool scheduled = false);
void completeEatCheck();
int  startTray();
int  requestTrayClose();
void updateTray();
const char* trayStateName();
const char* trayErrorText(int code);

void reconcileModeWithWifi() {
  if (finalizeStationSwitchPending) return;
  if (WiFi.status() == WL_CONNECTED) {
    if (currentMode != MODE_STATION) markStationConnected();
  } else if (currentMode == MODE_STATION || currentMode == MODE_CONNECTING) {
    currentMode = MODE_CONNECTING;
  }
}

void eepromWriteString(int addr, const String& str, int maxLen) {
  int len = min((int)str.length(), maxLen - 1);
  for (int i = 0; i < len; i++) EEPROM.write(addr + i, (uint8_t)str[i]);
  EEPROM.write(addr + len, 0);
}

String eepromReadString(int addr, int maxLen) {
  String result = "";
  for (int i = 0; i < maxLen; i++) {
    char c = (char)EEPROM.read(addr + i);
    if (c == '\0') break;
    result += c;
  }
  return result;
}

void saveCredentials(const String& ssid, const String& password) {
  EEPROM.write(EEPROM_MAGIC_ADDR, EEPROM_MAGIC);
  eepromWriteString(EEPROM_SSID_ADDR, ssid, EEPROM_SSID_LEN);
  eepromWriteString(EEPROM_PASS_ADDR, password, EEPROM_PASS_LEN);
  EEPROM.commit();
  Serial.println("[EEPROM] Credentials saved for SSID: " + ssid);
}

bool loadCredentials() {
  uint8_t magic = EEPROM.read(EEPROM_MAGIC_ADDR);
  if (magic != EEPROM_MAGIC) {
    Serial.println("[EEPROM] No saved credentials found.");
    return false;
  }
  savedSSID     = eepromReadString(EEPROM_SSID_ADDR, EEPROM_SSID_LEN);
  savedPassword = eepromReadString(EEPROM_PASS_ADDR, EEPROM_PASS_LEN);
  if (savedSSID.length() == 0) {
    Serial.println("[EEPROM] Saved SSID is empty.");
    return false;
  }
  Serial.println("[EEPROM] Loaded SSID: " + savedSSID);
  return true;
}

void eepromWriteU16(int addr, uint16_t v) {
  EEPROM.write(addr,     (uint8_t)(v & 0xFF));
  EEPROM.write(addr + 1, (uint8_t)(v >> 8));
}

uint16_t eepromReadU16(int addr) {
  return (uint16_t)EEPROM.read(addr) | ((uint16_t)EEPROM.read(addr + 1) << 8);
}

uint32_t hashString(const String& value) {
  uint32_t hash = 2166136261UL;
  for (unsigned int i = 0; i < value.length(); i++) {
    hash ^= (uint8_t)value[i];
    hash *= 16777619UL;
  }
  return hash;
}

void saveSchedulesToEEPROM() {
  EEPROM.write(EEPROM_SCHED_MAGIC_ADDR, EEPROM_SCHED_MAGIC);
  EEPROM.write(EEPROM_SCHED_COUNT_ADDR, (uint8_t)localScheduleCount);

  int addr = EEPROM_SCHED_ADDR;
  for (int i = 0; i < localScheduleCount; i++) {
    LocalSchedule& s = localSchedules[i];
    EEPROM.write(addr++, (uint8_t)(s.idHash & 0xFF));
    EEPROM.write(addr++, (uint8_t)((s.idHash >> 8) & 0xFF));
    EEPROM.write(addr++, (uint8_t)((s.idHash >> 16) & 0xFF));
    EEPROM.write(addr++, (uint8_t)((s.idHash >> 24) & 0xFF));
    EEPROM.write(addr++, s.hour);
    EEPROM.write(addr++, s.minute);
    EEPROM.write(addr++, s.enabled ? 1 : 0);
    EEPROM.write(addr++, s.daysMask);
    eepromWriteU16(EEPROM_PORTION_ADDR + i * 2, (uint16_t)(s.portionGrams * 10.0f + 0.5f));
  }
  EEPROM.commit();
  Serial.println("[Schedule] Saved " + String(localScheduleCount) + " schedule(s) to EEPROM.");
}

void loadSchedulesFromEEPROM() {
  localScheduleCount = 0;
  if (EEPROM.read(EEPROM_SCHED_MAGIC_ADDR) != EEPROM_SCHED_MAGIC) {
    Serial.println("[Schedule] No saved schedules found.");
    return;
  }

  int count = min((int)EEPROM.read(EEPROM_SCHED_COUNT_ADDR), MAX_SCHEDULES);
  int addr = EEPROM_SCHED_ADDR;
  for (int i = 0; i < count; i++) {
    uint32_t idHash = 0;
    idHash |= (uint32_t)EEPROM.read(addr++);
    idHash |= (uint32_t)EEPROM.read(addr++) << 8;
    idHash |= (uint32_t)EEPROM.read(addr++) << 16;
    idHash |= (uint32_t)EEPROM.read(addr++) << 24;
    localSchedules[i].idHash = idHash;
    localSchedules[i].hour = EEPROM.read(addr++);
    localSchedules[i].minute = EEPROM.read(addr++);
    localSchedules[i].enabled = EEPROM.read(addr++) == 1;
    localSchedules[i].daysMask = EEPROM.read(addr++);
    localSchedules[i].lastTriggeredYday = -1;
    uint16_t pg = eepromReadU16(EEPROM_PORTION_ADDR + i * 2);
    localSchedules[i].portionGrams = (pg > 60000) ? 0.0f : pg / 10.0f;
    if (localSchedules[i].hour < 24 && localSchedules[i].minute < 60) {
      localScheduleCount++;
    }
  }
  Serial.println("[Schedule] Loaded " + String(localScheduleCount) + " schedule(s) from EEPROM.");
}

bool isValidUid(const String& u) {
  if (u.length() < 6 || u.length() >= EEPROM_UID_LEN) return false;
  for (unsigned int i = 0; i < u.length(); i++) {
    char c = u[i];
    if (!isalnum((unsigned char)c) && c != '_' && c != '-') return false;
  }
  return true;
}

void saveDefaultPortion() {
  eepromWriteU16(EEPROM_DEF_PORTION_ADDR, (uint16_t)(defaultPortionGrams * 10.0f + 0.5f));
  EEPROM.commit();
}

void loadAccountLink() {
  if (EEPROM.read(EEPROM_UID_MAGIC_ADDR) == EEPROM_UID_MAGIC) {
    String u = eepromReadString(EEPROM_UID_ADDR, EEPROM_UID_LEN);
    if (isValidUid(u)) deviceUid = u;
  }
  uint16_t dp = eepromReadU16(EEPROM_DEF_PORTION_ADDR);
  defaultPortionGrams = (dp > 60000) ? 0.0f : dp / 10.0f;
  Serial.println(deviceUid.length() ? "[Account] Linked to an app account."
                                    : "[Account] Not linked to an app account yet.");
}

void clearAccountLink() {
  EEPROM.write(EEPROM_UID_MAGIC_ADDR, 0x00);
  EEPROM.commit();
  deviceUid = "";
}

void linkAccount(const String& uid) {
  if (uid == deviceUid) return;
  localScheduleCount = 0;
  saveSchedulesToEEPROM();
  defaultPortionGrams = 0.0f;
  saveDefaultPortion();
  lastRemoteConfigHash = 0;
  lastProcessedRemoteCommandId = "";
  lastAckedVersion = 0;
  pendingRecordCount = 0;
  eatCheck.active = false;
  deviceUid = uid;
  EEPROM.write(EEPROM_UID_MAGIC_ADDR, EEPROM_UID_MAGIC);
  eepromWriteString(EEPROM_UID_ADDR, uid, EEPROM_UID_LEN);
  EEPROM.commit();
  Serial.println("[Account] Feeder linked to app account.");
}

void clearCredentials() {
  EEPROM.write(EEPROM_MAGIC_ADDR, 0x00);
  EEPROM.commit();
  savedSSID     = "";
  savedPassword = "";
  clearAccountLink();
  Serial.println("[EEPROM] Credentials cleared.");
}

bool isSetupApUp() {
  return (WiFi.getMode() & WIFI_AP) && WiFi.softAPSSID() == String(AP_SSID);
}

void configureSetupAP() {
  IPAddress apIp;
  apIp.fromString(AP_IP_ADDR);
  IPAddress mask(255, 255, 255, 0);
  if (!(isSetupApUp() && WiFi.softAPIP() == apIp)) {
    WiFi.softAPConfig(apIp, apIp, mask);
    WiFi.softAP(AP_SSID, AP_PASSWORD, AP_CHANNEL, AP_HIDDEN, AP_MAX_CLIENTS);
  }
  WiFi.setSleepMode(WIFI_NONE_SLEEP);
}

void updateCaptiveDns() {
  if (isSetupApUp()) {
    if (!dnsRunning) {
      dnsServer.setErrorReplyCode(DNSReplyCode::NoError);
      dnsRunning = dnsServer.start(53, "*", WiFi.softAPIP());
      if (dnsRunning) Serial.println("[DNS] Captive portal DNS started.");
    }
    if (dnsRunning) dnsServer.processNextRequest();
  } else if (dnsRunning) {
    dnsServer.stop();
    dnsRunning = false;
    Serial.println("[DNS] Captive portal DNS stopped.");
  }
}

void startAPMode() {
  WiFi.mode(WIFI_AP_STA);
  configureSetupAP();
  Serial.println("[WiFi] AP started — SSID: " + String(AP_SSID));
  Serial.println("[WiFi] AP IP: " + WiFi.softAPIP().toString());
  currentMode = MODE_AP_ONLY;
}

void stopSetupAP() {
  if (dnsRunning) { dnsServer.stop(); dnsRunning = false; }
  WiFi.softAPdisconnect(true);
  WiFi.mode(WIFI_STA);
  Serial.println("[WiFi] Setup AP stopped.");
}

void markStationConnected() {
  stopSetupAP();
  currentMode = MODE_STATION;
  stationConnectInProgress = false;
  provisioningError = "";
  wifiRetryBackoffMs = WIFI_RETRY_INITIAL_MS;
  unsigned long nowMs = millis();
  lastFirebasePollMs = nowMs - FIREBASE_POLL_MS;
  lastHeartbeatMs = nowMs - FIREBASE_HEARTBEAT_MS;
  lastLiveWeightPushMs = nowMs - LIVE_WEIGHT_PUSH_MS;
  Serial.println("[WiFi] Connected! IP: " + WiFi.localIP().toString());
  syncClockIfNeeded(true);
  MDNS.close();
  if (MDNS.begin("petfeeder")) {
    Serial.println("[mDNS] Responder started -> http://petfeeder.local");
    MDNS.addService("http", "tcp", 80);
  }
}

void beginStationConnect() {
  if (savedSSID.length() == 0) return;
  if (WiFi.status() == WL_CONNECTED) {
    markStationConnected();
    return;
  }
  if (stationConnectInProgress && millis() - connectStartMs < WIFI_RECONNECT_TIMEOUT_MS) return;
  Serial.println("[WiFi] Connecting to: " + savedSSID);
  WiFi.mode(WIFI_AP_STA);
  WiFi.disconnect(false);
  WiFi.begin(savedSSID.c_str(), savedPassword.c_str());
  stationConnectInProgress = true;
  currentMode    = MODE_CONNECTING;
  connectStartMs = millis();
}

bool tryCompleteConnection() {
  if (WiFi.status() == WL_CONNECTED) {
    markStationConnected();
    return true;
  }
  if (millis() - connectStartMs > WIFI_RECONNECT_TIMEOUT_MS) {
    Serial.println("[WiFi] Connection timed out — keeping setup AP and retrying STA.");
    stationConnectInProgress = false;
    nextWifiRetryMs = millis() + wifiRetryBackoffMs;
    wifiRetryBackoffMs = min(wifiRetryBackoffMs * 2, (unsigned long)WIFI_RETRY_MAX_MS);
    if (WiFi.getMode() != WIFI_AP_STA) WiFi.mode(WIFI_AP_STA);
    startAPMode();
    return false;
  }
  return false;
}

void updateWifiRetry() {
  if (WiFi.status() == WL_CONNECTED) return;
  if (savedSSID.length() == 0) return;
  if (stationConnectInProgress) return;
  if ((long)(millis() - nextWifiRetryMs) < 0) return;
  beginStationConnect();
}

void onStationDisconnected(const WiFiEventStationModeDisconnected& event) {
  lastDisconnectReason = event.reason;
  stationConnectInProgress = false;
  currentMode = MODE_CONNECTING;
  if (event.reason == 201) provisioningError = "SSID not found or 5 GHz-only network";
  else if (event.reason == 202 || event.reason == 15) provisioningError = "Wrong password";
  else provisioningError = "Disconnected, reason " + String(event.reason);
  Serial.println("[WiFi] Disconnected. reason=" + String(event.reason)
                 + " heap=" + String(ESP.getFreeHeap()));
  if (savedSSID.length() > 0) {
    if (WiFi.getMode() != WIFI_AP_STA) WiFi.mode(WIFI_AP_STA);
    configureSetupAP();
    nextWifiRetryMs = millis() + wifiRetryBackoffMs;
    wifiRetryBackoffMs = min(wifiRetryBackoffMs * 2, (unsigned long)WIFI_RETRY_MAX_MS);
  }
}

void onStationGotIp(const WiFiEventStationModeGotIP& event) {
  Serial.println("[WiFi] Got IP: " + event.ip.toString()
                 + " heap=" + String(ESP.getFreeHeap()));
  markStationConnected();
}

bool isClockSynced() {
  time_t now = time(nullptr);
  return now > 1609459200L;
}

void syncClockIfNeeded(bool force) {
  if (WiFi.status() != WL_CONNECTED) return;
  if (!force) {
    unsigned long elapsed = millis() - lastNtpSyncAttemptMs;
    if (isClockSynced()  && elapsed < NTP_SYNC_INTERVAL_MS) return;
    if (!isClockSynced() && elapsed < 60000UL)              return;
  }

  lastNtpSyncAttemptMs = millis();
  configTime(TZ_OFFSET_SECONDS, 0, "pool.ntp.org", "time.nist.gov", "time.google.com");
  Serial.println("[Time] NTP sync requested.");
}

uint8_t dayMaskFromTmWday(int tmWday) {
  int appDayIndex = (tmWday + 6) % 7;
  return (uint8_t)(1 << appDayIndex);
}

void updateScheduledFeeding() {
  if (currentMode != MODE_STATION || WiFi.status() != WL_CONNECTED) return;
  if (millis() - lastScheduleCheckMs < SCHEDULE_CHECK_MS) return;
  lastScheduleCheckMs = millis();

  syncClockIfNeeded(false);
  if (!isClockSynced()) return;

  time_t now = time(nullptr);
  struct tm* local = localtime(&now);
  if (local == nullptr) return;
  currentLocalHour = local->tm_hour;
  currentLocalMinute = local->tm_min;
  currentLocalWday = local->tm_wday;

  uint8_t todayMask = dayMaskFromTmWday(local->tm_wday);
  for (int i = 0; i < localScheduleCount; i++) {
    LocalSchedule& s = localSchedules[i];
    if (!s.enabled) continue;
    if (s.hour != local->tm_hour || s.minute != local->tm_min) continue;
    if (s.daysMask != 0 && (s.daysMask & todayMask) == 0) continue;
    if (s.lastTriggeredYday == local->tm_yday) continue;

    if (!isFeeding) {
      s.lastTriggeredYday = local->tm_yday;
      Serial.println("[Schedule] Feeding triggered at " + String(s.hour) + ":" + String(s.minute));
      triggerFeed(s.portionGrams, true);
      feedIsScheduled   = true;
      feedScheduleHash  = s.idHash;
      feedStartEpochSec = (uint32_t)now;
      feedSlotEpochSec  = (uint32_t)now - (uint32_t)local->tm_sec;
      char dbuf[8];
      strftime(dbuf, sizeof(dbuf), "%m/%d", local);
      feedDateStr = String(dbuf);
    }
  }
}

String buildSchedulesJson() {
  StaticJsonDocument<1024> doc;
  doc["count"] = localScheduleCount;
  JsonArray schedules = doc.createNestedArray("schedules");
  for (int i = 0; i < localScheduleCount; i++) {
    JsonObject item = schedules.createNestedObject();
    item["hour"] = localSchedules[i].hour;
    item["minute"] = localSchedules[i].minute;
    item["enabled"] = localSchedules[i].enabled;
    item["daysMask"] = localSchedules[i].daysMask;
    item["portionGrams"] = localSchedules[i].portionGrams;
    item["lastTriggeredYday"] = localSchedules[i].lastTriggeredYday;
  }
  String output;
  serializeJson(doc, output);
  return output;
}

void applySchedulesFromJson(JsonArray schedules) {
  LocalSchedule previousSchedules[MAX_SCHEDULES];
  int previousScheduleCount = localScheduleCount;
  for (int i = 0; i < previousScheduleCount; i++) {
    previousSchedules[i] = localSchedules[i];
  }

  localScheduleCount = 0;
  for (JsonObject item : schedules) {
    if (localScheduleCount >= MAX_SCHEDULES) break;

    int hour = item.containsKey("hour") ? item["hour"].as<int>() : (item["timeHour"] | -1);
    int minute = item.containsKey("minute") ? item["minute"].as<int>() : (item["timeMinute"] | -1);
    if (hour < 0 || hour > 23 || minute < 0 || minute > 59) continue;

    String id = item.containsKey("id")
        ? item["id"].as<String>()
        : (item["scheduleId"] | "");
    if (id.length() == 0) id = String(hour) + ":" + String(minute) + ":" + String(localScheduleCount);

    LocalSchedule& s = localSchedules[localScheduleCount++];
    s.idHash = hashString(id);
    s.hour = (uint8_t)hour;
    s.minute = (uint8_t)minute;
    s.enabled = item["enabled"] | true;
    s.daysMask = repeatDaysToMask(item["repeatDays"].as<JsonArray>());
    s.lastTriggeredYday = -1;
    bool hasPortion = item.containsKey("portionGrams");
    s.portionGrams = hasPortion ? item["portionGrams"].as<float>() : 0.0f;

    for (int i = 0; i < previousScheduleCount; i++) {
      LocalSchedule& previous = previousSchedules[i];
      if (previous.idHash == s.idHash
          && previous.hour == s.hour
          && previous.minute == s.minute) {
        s.lastTriggeredYday = previous.lastTriggeredYday;
        if (!hasPortion) s.portionGrams = previous.portionGrams;
        break;
      }
    }
  }
}

String firebaseUrl(const String& path) {
  String url = String(FIREBASE_DB_URL) + path;
  if (String(FIREBASE_AUTH_TOKEN).length() > 0) url += "?auth=" + String(FIREBASE_AUTH_TOKEN);
  return url;
}

String userPath(const String& sub) {
  return "/users/" + deviceUid + sub;
}

String firebaseGet(const String& path) {
  static BearSSL::WiFiClientSecure client;
  client.setInsecure();
  client.setBufferSizes(1024, 512);
  client.setTimeout(FIREBASE_HTTP_TIMEOUT_MS);
  HTTPClient http;
  http.setTimeout(FIREBASE_HTTP_TIMEOUT_MS);
  http.setReuse(true);
  if (!http.begin(client, firebaseUrl(path))) return "";
  int code = http.GET();
  String body = (code > 0) ? http.getString() : "";
  http.end();
  return (code >= 200 && code < 300) ? body : "";
}

bool firebasePatch(const String& path, const String& payload) {
  static BearSSL::WiFiClientSecure client;
  client.setInsecure();
  client.setBufferSizes(1024, 512);
  client.setTimeout(FIREBASE_HTTP_TIMEOUT_MS);
  HTTPClient http;
  http.setTimeout(FIREBASE_HTTP_TIMEOUT_MS);
  http.setReuse(true);
  if (!http.begin(client, firebaseUrl(path))) return false;
  http.addHeader("Content-Type", "application/json");
  int code = http.sendRequest("PATCH", payload);
  http.end();
  if (code < 200 || code >= 300) {
    IPAddress dnsIp;
    bool dnsOk = WiFi.hostByName("smart-ped-feeder-default-rtdb.firebaseio.com", dnsIp);
    Serial.println("[Firebase] PATCH failed, HTTP " + String(code) + " (" + HTTPClient::errorToString(code)
        + ") heap=" + String(ESP.getFreeHeap()) + " wifi=" + String((int)WiFi.status())
        + " rssi=" + String(WiFi.RSSI()) + " dns=" + (dnsOk ? dnsIp.toString() : String("FAILED"))
        + " clockSynced=" + String(isClockSynced() ? "yes" : "no"));
  }
  return code >= 200 && code < 300;
}

bool liveWeightMoved() {
  if (!scaleAvailable) return false;
  float w = currentWeightGrams > 0.0f ? currentWeightGrams : 0.0f;
  return fabs(w - lastPushedWeight) >= WEIGHT_PUSH_DELTA_G;
}

bool hasValidLiveWeight() {
  return scaleAvailable && lastWeightSampleMs > 0
      && millis() - lastWeightSampleMs <= WEIGHT_TIMEOUT_MS;
}

bool weightStableNow() {
  if (!hasValidLiveWeight() || weightSampleCount < SAMPLES) return false;
  float lo = weightSamples[0], hi = weightSamples[0];
  for (int i = 1; i < SAMPLES; i++) {
    if (weightSamples[i] < lo) lo = weightSamples[i];
    if (weightSamples[i] > hi) hi = weightSamples[i];
  }
  return (hi - lo) <= STABLE_SPREAD_G;
}

bool pushLiveWeightToFirebase(bool force) {
  if (WiFi.status() != WL_CONNECTED) return false;
  if (deviceUid.length() == 0) return false;
  if (!hasValidLiveWeight()) return false;
  unsigned long nowMs = millis();
  float liveWeight = currentWeightGrams > 0.0f ? currentWeightGrams : 0.0f;
  bool feedLiveNow = eatCheck.active;
  bool feedLiveEnd = !feedLiveNow && liveFeedEndPending;
  bool feedLiveDirty = liveFeedDirty || feedLiveEnd;
  if (!force) {
    bool changed = fabs(liveWeight - lastPushedWeight) >= WEIGHT_PUSH_DELTA_G || feedLiveDirty;
    bool expired = nowMs - lastLiveWeightPushMs >= LIVE_WEIGHT_MAX_AGE_MS;
    if (nowMs - lastLiveWeightPushMs < LIVE_WEIGHT_PUSH_MS) return false;
    if (!changed && !expired) return false;
  }

  String payload = "{\"connected\":true"
      ",\"lastSeen\":{\".sv\":\"timestamp\"}"
      ",\"currentWeightAvailable\":true"
      ",\"currentWeight\":" + String(liveWeight, 1) +
      ",\"weightUpdatedAt\":{\".sv\":\"timestamp\"}";
  if (feedLiveNow) {
    payload += ",\"liveFeeding\":{\"active\":true,\"feedingId\":\"" + eatCheck.id + "\""
        ",\"target\":" + String(eatCheck.target, 1) +
        ",\"consumed\":" + String(eatCheck.consumed, 1) +
        ",\"remaining\":" + String(eatCheck.lastRemaining, 1) +
        ",\"baseline\":" + String(eatCheck.afterDispense, 1) +
        ",\"bowlRemoved\":" + String(eatCheck.bowlRemoved ? "true" : "false") +
        ",\"updatedAt\":{\".sv\":\"timestamp\"}}";
  } else if (feedLiveEnd) {
    payload += ",\"liveFeeding\":{\"active\":false}";
  }
  payload += "}";

  bool ok = firebasePatch(userPath("/device_status.json"), payload);
  if (ok) {
    if (feedLiveNow) {
      liveFeedDirty = false;
      liveFeedSentRemaining = eatCheck.lastRemaining;
      liveFeedSentConsumed = eatCheck.consumed;
      liveFeedSentBowlRemoved = eatCheck.bowlRemoved;
    } else if (feedLiveEnd) {
      liveFeedEndPending = false;
      liveFeedDirty = false;
    }
    lastHeartbeatMs = nowMs;
    lastPushedWeight = liveWeight;
    weightSkipLogged = false;
    Serial.println("[Firebase] Live weight updated: " + String(liveWeight, 1) + " g");
  }
  lastLiveWeightPushMs = nowMs;
  return ok;
}

bool sendHeartbeat(long ackVersion) {
  String payload = "{\"connected\":true,\"lastSeen\":{\".sv\":\"timestamp\"}";
  float liveWeight = currentWeightGrams > 0.0f ? currentWeightGrams : 0.0f;
  payload += ",\"currentWeightAvailable\":" + String(scaleAvailable ? "true" : "false");
  if (scaleAvailable) {
    payload += ",\"currentWeight\":" + String(liveWeight, 1)
            + ",\"weightUpdatedAt\":{\".sv\":\"timestamp\"}";
  } else if (!weightSkipLogged) {
    payload += ",\"currentWeight\":null,\"weightUpdatedAt\":null";
    weightSkipLogged = true;
    Serial.println("[Firebase] Live weight not sent - HX711 not available.");
  } else {
    payload += ",\"currentWeight\":null,\"weightUpdatedAt\":null";
  }
  if (ackVersion > 0) payload += ",\"applied_schedule_version\":" + String(ackVersion);
  payload += "}";
  bool ok = firebasePatch(userPath("/device_status.json"), payload);
  if (ok) {
    lastHeartbeatMs = millis();
    if (scaleAvailable) {
      lastLiveWeightPushMs = lastHeartbeatMs;
      lastPushedWeight = liveWeight;
      Serial.println("[Firebase] Live weight sent: " + String(liveWeight, 1) + " g");
    }
    if (ackVersion > 0) lastAckedVersion = ackVersion;
  }
  return ok;
}

bool applyRemoteConfig(JsonObject config, long version, long appliedInDb) {
  if (config.isNull()) return false;
  String serialized;
  serializeJson(config, serialized);
  uint32_t configHash = hashString(serialized);
  if (configHash == lastRemoteConfigHash) return false;

  if (config.containsKey("portionGrams")) {
    float pg = config["portionGrams"].as<float>();
    if (pg >= 0.0f && pg != defaultPortionGrams) {
      defaultPortionGrams = pg;
      saveDefaultPortion();
    }
  }

  JsonArray schedules = config["schedule"].as<JsonArray>();
  if (!schedules.isNull()) {
    applySchedulesFromJson(schedules);
    saveSchedulesToEEPROM();
  } else if (version > 0 && appliedInDb != version) {
    localScheduleCount = 0;
    saveSchedulesToEEPROM();
  }

  lastRemoteConfigHash = configHash;
  Serial.println("[Firebase] Remote config applied. Schedules: " + String(localScheduleCount)
                 + ", default portion: " + String(defaultPortionGrams, 1) + " g");
  return true;
}

void handleRemoteCommand(JsonObject cmd) {
  if (cmd.isNull()) return;
  String status    = cmd["status"] | "";
  String type      = cmd["type"] | "";
  String commandId = cmd["commandId"] | "";
  if (status != "pending" || (type != "manual_feed" && type != "dispense_water" && type != "open_tray" && type != "close_tray") || commandId.length() == 0) return;
  if (commandId == lastProcessedRemoteCommandId) return;

  double expiresAt = cmd["expiresAt"].as<double>();
  if (expiresAt > 0 && isClockSynced() && ((double)time(nullptr) * 1000.0) > expiresAt) {
    lastProcessedRemoteCommandId = commandId;
    firebasePatch(userPath("/device_status/remote_command.json"), "{\"status\":\"expired\"}");
    Serial.println("[Firebase] Remote manual feed expired, ignored: " + commandId);
    return;
  }

  if (type == "open_tray" || type == "close_tray") {
    lastProcessedRemoteCommandId = commandId;
    bool opening = (type == "open_tray");
    if (commandId == trayLastCommandId) {
      firebasePatch(userPath("/device_status/remote_command.json"),
          String("{\"status\":\"") + (trayState == TRAY_IDLE ? "completed" : "accepted") + "\"}");
      Serial.println("[Tray] Duplicate remote tray command ignored: " + commandId);
      return;
    }
    int rc = TRAY_OK;
    if (opening) {
      if (trayState != TRAY_IDLE)                                          rc = TRAY_ERR_BUSY;
      else if (trayHasRun && millis() - trayLastEndMs < TRAY_COOLDOWN_MS)  rc = TRAY_ERR_COOLDOWN;
      else if (isFeeding)                                                  rc = TRAY_ERR_FEEDING;
    } else {
      if (trayState == TRAY_IDLE)                                          rc = TRAY_ERR_NOT_OPEN;
      else if (trayState == TRAY_CLOSING || trayState == TRAY_STOPPING)    rc = TRAY_ERR_BUSY;
    }
    if (rc != TRAY_OK) {
      firebasePatch(userPath("/device_status/remote_command.json"),
          String("{\"status\":\"rejected\",\"error\":\"") + trayErrorText(rc)
          + "\",\"completedAtMs\":" + String(millis()) + "}");
      Serial.println("[Tray] Remote " + type + " rejected (" + String(trayErrorText(rc)) + "): " + commandId);
      return;
    }
    firebasePatch(userPath("/device_status/remote_command.json"),
        String("{\"status\":\"accepted\",\"acceptedAtMs\":") + String(millis()) + "}");
    rc = opening ? startTray() : requestTrayClose();
    if (rc != TRAY_OK) {
      firebasePatch(userPath("/device_status/remote_command.json"),
          String("{\"status\":\"rejected\",\"error\":\"") + trayErrorText(rc)
          + "\",\"completedAtMs\":" + String(millis()) + "}");
      Serial.println("[Tray] Remote " + type + " rejected after ack (" + String(trayErrorText(rc)) + "): " + commandId);
      return;
    }
    trayLastCommandId   = commandId;
    trayRemoteCommandId = commandId;
    Serial.println("[Tray] Remote " + type + " started: " + commandId);
    return;
  }

  if (type == "dispense_water") {
    float ml = cmd["volumeMl"] | 0.0f;
    lastProcessedRemoteCommandId = commandId;
    bool started = startWater(ml);
    firebasePatch(userPath("/device_status/remote_command.json"),
        String("{\"status\":\"") + (started ? "completed" : "rejected") + "\",\"completedAtMs\":" + String(millis()) + "}");
    Serial.println("[Firebase] Remote water dispense " + String(started ? "started: " : "rejected: ") + commandId);
    return;
  }

  float portion = cmd["portionGrams"] | 0.0f;
  lastProcessedRemoteCommandId = commandId;
  if (!isFeeding) { feedManualRecordId = commandId; triggerFeed(portion); }
  firebasePatch(userPath("/device_status/remote_command.json"),
      "{\"status\":\"completed\",\"completedAtMs\":" + String(millis()) + "}");
  Serial.println("[Firebase] Remote manual feed executed: " + commandId);
}

void pollDeviceStatus() {
  String body = firebaseGet(userPath("/device_status.json"));
  if (body.length() == 0 || body == "null") {
    if (millis() - lastHeartbeatMs >= FIREBASE_HEARTBEAT_MS || liveWeightMoved()) sendHeartbeat(0);
    return;
  }

  StaticJsonDocument<192> filter;
  filter["remote_config"]             = true;
  filter["remote_command"]            = true;
  filter["schedule_version"]          = true;
  filter["applied_schedule_version"]  = true;

  DynamicJsonDocument doc(6144);
  DeserializationError err = deserializeJson(doc, body, DeserializationOption::Filter(filter));
  if (err) {
    if (millis() - lastHeartbeatMs >= FIREBASE_HEARTBEAT_MS || liveWeightMoved()) sendHeartbeat(0);
    return;
  }

  long version     = doc["schedule_version"] | 0L;
  long appliedInDb = doc["applied_schedule_version"] | 0L;

  applyRemoteConfig(doc["remote_config"].as<JsonObject>(), version, appliedInDb);
  handleRemoteCommand(doc["remote_command"].as<JsonObject>());

  if (version > 0 && (version != appliedInDb || version != lastAckedVersion)) {
    if (sendHeartbeat(version)) Serial.println("[Firebase] Acked schedule version " + String(version));
    return;
  }
  if (millis() - lastHeartbeatMs >= FIREBASE_HEARTBEAT_MS || liveWeightMoved()) sendHeartbeat(0);
}

void flushPendingRecords() {
  while (pendingRecordCount > 0) {
    if (!firebasePatch(userPath("/feeding_records/" + pendingRecords[0].id + ".json"),
                       pendingRecords[0].json)) return;
    Serial.println("[Record] Uploaded " + pendingRecords[0].id);
    for (int i = 1; i < pendingRecordCount; i++) pendingRecords[i - 1] = pendingRecords[i];
    pendingRecordCount--;
  }
  if (liveRemainingJson.length() > 0) {
    if (!eatCheck.active || eatCheck.id != liveRemainingId) {
      liveRemainingJson = "";
    } else if (firebasePatch(userPath("/feeding_records/" + liveRemainingId + ".json"),
                             liveRemainingJson)) {
      liveRemainingJson = "";
    }
  }
}

void queueFeedingRecord(const String& id, const String& json) {
  if (pendingRecordCount >= MAX_PENDING_RECORDS) {
    for (int i = 1; i < pendingRecordCount; i++) pendingRecords[i - 1] = pendingRecords[i];
    pendingRecordCount--;
  }
  pendingRecords[pendingRecordCount].id   = id;
  pendingRecords[pendingRecordCount].json = json;
  pendingRecordCount++;
}

void startEatCheck(const String& id, float afterDispenseGrams) {
  if (eatCheck.active) completeEatCheck();
  eatCheck.active        = true;
  eatCheck.id            = id;
  eatCheck.afterDispense = afterDispenseGrams > 0.0f ? afterDispenseGrams : 0.0f;
  eatCheck.dueMs         = millis() + EAT_WINDOW_MS;
  eatCheck.retries       = 0;
  eatCheck.nextLiveMs    = millis() + EAT_LIVE_PATCH_MS;
  eatCheck.refWeight     = eatCheck.afterDispense;
  eatCheck.consumed      = 0.0f;
  eatCheck.lastRemaining = eatCheck.afterDispense;
  eatCheck.bowlRemoved   = afterDispenseGrams < EAT_BOWL_REMOVED_G;
  eatCheck.nextSampleMs  = millis() + EAT_SAMPLE_MS;
  eatCheck.target        = feedTargetGrams;
  liveRemainingJson      = "";
  lastFinalStatsValid    = false;
  liveFeedEndPending     = false;
  liveFeedDirty          = true;
  liveFeedSentRemaining  = -1000.0f;
  Serial.println("[Eat] Bowl after dispensing: " + String(eatCheck.afterDispense, 1)
                 + " g. Live statistics run until "
                 + (hasEnabledSchedule() ? String("the next schedule.")
                                         : "the end of the " + String(EAT_WINDOW_MS / 60000UL) + " min eating window."));
}

void trackManualFeedRecord() {
  if (!scaleAvailable || deviceUid.length() == 0 || feedRecordId.length() == 0) {
    Serial.println("[Record] Manual feed NOT tracked - scale " + String(scaleAvailable ? "ok" : "unavailable")
                   + ", account " + String(deviceUid.length() > 0 ? "linked" : "NOT linked")
                   + ", record id " + String(feedRecordId.length() > 0 ? "present" : "missing (feed started without the app's record)"));
    return;
  }
  float after = currentWeightGrams > 0.0f ? currentWeightGrams : 0.0f;
  String json = "{\"weightBeforeFeed\":" + String(feedStartWeight > 0.0f ? feedStartWeight : 0.0f, 1) +
      ",\"weightAfterDispense\":" + String(after, 1) +
      ",\"remainingWeight\":" + String(after, 1) +
      ",\"consumedWeight\":0.0"
      ",\"consumptionRecorded\":false"
      ",\"eatWindowMs\":" + String(EAT_WINDOW_MS);
  if (feedWeightMode) {
    float added = feedBowlFull ? 0.0f : after - feedStartWeight;
    if (added < 0.0f) added = 0.0f;
    json += ",\"dispensedWeight\":" + String(added, 1)
          + ",\"bowlAlreadyFull\":" + String(feedBowlFull ? "true" : "false");
    if (feedTimedOut) json += ",\"status\":\"Anomaly\",\"anomalyFlag\":true";
  }
  json += "}";
  queueFeedingRecord(feedRecordId, json);
  startEatCheck(feedRecordId, after);
}

bool sampleEatConsumption() {
  if (!eatCheck.active || !weightStableNow()) return false;
  float s = currentWeightGrams > 0.0f ? currentWeightGrams : 0.0f;

  if (eatCheck.bowlRemoved) {
    if (s >= EAT_BOWL_REMOVED_G) {
      eatCheck.bowlRemoved = false;
      eatCheck.refWeight = s;
      eatCheck.lastRemaining = s;
      Serial.println("[Eat] Bowl back on scale, new reference " + String(s, 1) + " g.");
      return true;
    }
    return false;
  }
  if (s < EAT_BOWL_REMOVED_G && eatCheck.refWeight >= EAT_BOWL_REMOVED_G) {
    eatCheck.bowlRemoved = true;
    Serial.println("[Eat] Bowl removed - consumption frozen.");
    return false;
  }

  eatCheck.lastRemaining = s;
  float delta = eatCheck.refWeight - s;
  if (delta >= EAT_DISTURB_DROP_G) {
    eatCheck.refWeight = s;
    Serial.println("[Eat] Sudden drop of " + String(delta, 1) + " g ignored (disturbance).");
    return true;
  }
  if (delta >= EAT_NOISE_G) {
    eatCheck.consumed += delta;
    if (eatCheck.consumed > eatCheck.afterDispense) eatCheck.consumed = eatCheck.afterDispense;
    eatCheck.refWeight = s;
    Serial.println("[Eat] Eaten " + String(delta, 1) + " g, total " + String(eatCheck.consumed, 1) + " g");
    return true;
  }
  if (delta <= -EAT_REFILL_G) {
    eatCheck.refWeight = s;
    Serial.println("[Eat] Refill detected, new reference " + String(s, 1) + " g.");
    return true;
  }
  return false;
}

void completeEatCheck() {
  if (!eatCheck.active) return;
  sampleEatConsumption();
  eatCheck.active = false;
  liveRemainingJson = "";
  float after = eatCheck.lastRemaining;
  float consumed = eatCheck.consumed;
  lastFinalRemaining  = after;
  lastFinalStatsValid = hasValidLiveWeight() && !eatCheck.bowlRemoved && after >= 0.0f;
  liveFeedEndPending  = true;
  String json = "{\"weightAfterEating\":" + String(after, 1) +
      ",\"consumedWeight\":" + String(consumed, 1) +
      ",\"remainingWeight\":" + String(after, 1) +
      ",\"consumptionRecorded\":true";
  if (isClockSynced()) json += ",\"consumptionTime\":" + String((uint32_t)time(nullptr)) + "000";
  json += "}";
  queueFeedingRecord(eatCheck.id, json);
  Serial.println("[Eat] " + eatCheck.id + ": after dispense " + String(eatCheck.afterDispense, 1)
                 + " g, now " + String(after, 1) + " g -> consumed " + String(consumed, 1)
                 + " g, remaining " + String(after, 1) + " g"
                 + (lastFinalStatsValid ? "" : " (saved statistics NOT valid)"));
}

void updateEatCheck() {
  if (!eatCheck.active || isFeeding) return;
  if (hasEnabledSchedule() || (long)(millis() - eatCheck.dueMs) < 0) {
    if ((long)(millis() - eatCheck.nextSampleMs) >= 0) {
      eatCheck.nextSampleMs = millis() + EAT_SAMPLE_MS;
      bool changed = sampleEatConsumption();
      if (fabs(eatCheck.lastRemaining - liveFeedSentRemaining) >= WEIGHT_PUSH_DELTA_G
          || fabs(eatCheck.consumed - liveFeedSentConsumed) >= 0.05f
          || eatCheck.bowlRemoved != liveFeedSentBowlRemoved) {
        liveFeedDirty = true;
      }
      if (changed || (long)(millis() - eatCheck.nextLiveMs) >= 0) {
        eatCheck.nextLiveMs = millis() + EAT_LIVE_PATCH_MS;
        liveRemainingId   = eatCheck.id;
        liveRemainingJson = "{\"consumedWeight\":" + String(eatCheck.consumed, 1) +
                            ",\"remainingWeight\":" + String(eatCheck.lastRemaining, 1) + "}";
      }
    }
    return;
  }
  if (!hasValidLiveWeight()) {
    if (++eatCheck.retries > EAT_MAX_RETRIES) {
      eatCheck.active = false;
      lastFinalStatsValid = false;
      liveFeedEndPending  = true;
      Serial.println("[Eat] Gave up - scale unavailable.");
    } else {
      eatCheck.dueMs = millis() + EAT_RETRY_MS;
    }
    return;
  }
  completeEatCheck();
}

void finalizeScheduledFeedRecord() {
  if (!scaleAvailable || feedTargetGrams <= 0.0f || !feedWeightMode) {
    Serial.println("[Record] Skipped - needs the load cell and a portion size.");
    return;
  }
  if (deviceUid.length() == 0) {
    Serial.println("[Record] Scheduled feed NOT tracked - account NOT linked (no UID on the feeder).");
    return;
  }

  float after = currentWeightGrams > 0.0f ? currentWeightGrams : 0.0f;
  float before = feedStartWeight > 0.0f ? feedStartWeight : 0.0f;
  float dispensed = feedBowlFull ? 0.0f : after - before;
  if (dispensed < 0.0f) dispensed = 0.0f;
  feedMeasuredWeight = after;
  float deviationGrams = after - feedTargetGrams;
  float deviationPercent = (deviationGrams / feedTargetGrams) * 100.0f;
  bool anomaly = !feedBowlFull
              && (feedTimedOut
                  || after < feedTargetGrams * PORTION_LOW_RATIO
                  || after > feedTargetGrams * PORTION_HIGH_RATIO);

  String id = "sched_" + String(feedSlotEpochSec) + "_" + String(feedScheduleHash, HEX);
  String json = "{\"feedingId\":\"" + id + "\""
      ",\"scheduledTime\":" + String(feedSlotEpochSec) + "000"
      ",\"actualTime\":" + String(feedStartEpochSec) + "000"
      ",\"scheduledPortion\":" + String(feedTargetGrams, 1) +
      ",\"dispensedWeight\":" + String(dispensed, 1) +
      ",\"measuredWeight\":" + String(feedMeasuredWeight, 1) +
      ",\"recommendedWeight\":" + String(feedTargetGrams, 1) +
      ",\"deviationGrams\":" + String(deviationGrams, 1) +
      ",\"deviationPercent\":" + String(deviationPercent, 1) +
      ",\"weightRecorded\":true"
      ",\"bowlAlreadyFull\":" + String(feedBowlFull ? "true" : "false") +
      ",\"weightBeforeFeed\":" + String(before, 1) +
      ",\"weightAfterDispense\":" + String(after, 1) +
      ",\"consumptionRecorded\":false"
      ",\"eatWindowMs\":" + String(EAT_WINDOW_MS) +
      ",\"consumedWeight\":0.0"
      ",\"remainingWeight\":" + String(after, 1) +
      ",\"status\":\"" + String(anomaly ? "Anomaly" : "Completed") + "\""
      ",\"anomalyFlag\":" + String(anomaly ? "true" : "false") +
      ",\"date\":\"" + feedDateStr + "\"}";
  queueFeedingRecord(id, json);
  startEatCheck(id, after);
  Serial.println("[Record] Queued " + id + " - target " + String(feedTargetGrams, 1)
                 + " g, bowl before " + String(before, 1) + " g, dispensed "
                 + String(dispensed, 1) + " g, after " + String(after, 1) + " g"
                 + (feedBowlFull ? " (bowl already full)" : "")
                 + (feedTimedOut ? " (ANOMALY: max open time hit)" : ""));
}

void updateFirebaseRemoteControl() {
  if (WiFi.status() != WL_CONNECTED) return;
  if (deviceUid.length() == 0) return;
  if (finalizeStationSwitchPending) return;
  if (isFeeding) return;
  if (trayState != TRAY_IDLE && trayState != TRAY_HOLDING) return;
  pushLiveWeightToFirebase(false);
  if (millis() - lastFirebasePollMs < FIREBASE_POLL_MS) return;
  lastFirebasePollMs = millis();

  pollDeviceStatus();
  flushPendingRecords();
}

bool verifyWifiConnection(const String& ssid, const String& pass,
                          String& ipOut, String& reason) {

  WiFi.disconnect();
  delay(200);

  WiFi.mode(WIFI_AP_STA);
  configureSetupAP();
  currentMode = MODE_CONNECTING;
  connectStartMs = millis();

  WiFi.begin(ssid.c_str(), pass.c_str());
  Serial.println("[WiFi] [verify] Attempting: " + ssid);

  unsigned long start = millis();
  while (millis() - start < WIFI_CONNECT_TIMEOUT_MS) {
    wl_status_t st = WiFi.status();

    if (st == WL_CONNECTED) {
      ipOut = WiFi.localIP().toString();
      Serial.println("[WiFi] [verify] Connected! IP: " + ipOut);
      return true;
    }

    if (st == WL_WRONG_PASSWORD) {
      reason = "Wrong password";
      Serial.println("[WiFi] [verify] Wrong password.");
      WiFi.disconnect();
      currentMode = MODE_AP_ONLY;
      return false;
    }

    if (st == WL_NO_SSID_AVAIL) {
      reason = "Network not found";
      Serial.println("[WiFi] [verify] SSID not found.");
      WiFi.disconnect();
      currentMode = MODE_AP_ONLY;
      return false;
    }

    if (st == WL_CONNECT_FAILED) {
      reason = "Connection failed";
      Serial.println("[WiFi] [verify] Connection failed.");
      WiFi.disconnect();
      currentMode = MODE_AP_ONLY;
      return false;
    }

    updateCaptiveDns();
    yield();
    delay(200);
  }

  reason = "Connection timed out";
  Serial.println("[WiFi] [verify] Timed out.");
  WiFi.disconnect();
  currentMode = MODE_AP_ONLY;
  return false;
}

String generateProvisioningToken() {
  uint32_t seed = ESP.getChipId() ^ micros() ^ random(0xFFFFFF);
  String token = String(seed, HEX) + String(millis(), HEX);
  token.toUpperCase();
  return token;
}

void startProvisioningSession() {
  provisionToken = generateProvisioningToken();
  provisionTokenExpiryMs = millis() + PROVISION_TOKEN_TTL_MS;
  Serial.println("[Provisioning] New token issued.");
}

bool isProvisionTokenValid(const String& token) {
  if (provisionToken.length() == 0) return false;
  if (token != provisionToken) return false;
  if ((long)(millis() - provisionTokenExpiryMs) > 0) return false;
  return true;
}

void triggerFeed(float portionGrams, bool scheduled) {
  if (isFeeding) return;
  if (portionGrams <= 0.0f) portionGrams = defaultPortionGrams;
  feedTargetGrams     = portionGrams;
  feedStartWeight     = fastWeightGrams;
  feedIsScheduled     = scheduled;
  feedFinalizePending = false;
  feedBowlFull        = false;
  feedTimedOut        = false;
  feedNeedGrams       = 0.0f;
  feedStopAt          = 0.0f;
  bool hadEatCheck = eatCheck.active;
  if (hadEatCheck) completeEatCheck();
  bool  useSavedRemaining = scheduled && hadEatCheck && lastFinalStatsValid;
  float savedRemaining    = lastFinalRemaining;
  lastFinalStatsValid     = false;
  feedRecordId        = scheduled ? "" : feedManualRecordId;
  feedManualRecordId  = "";

  bool scaleOk = scaleAvailable && hasValidLiveWeight();
  feedWeightMode = scaleOk && feedTargetGrams > 0.0f;
  if (!feedWeightMode) {
    Serial.println("[Feeder] TIMED fallback (" + String(SERVO_HOLD_MS) + " ms): "
                   + String(scaleOk ? "no target portion set (portionGrams = 0)" : "scale unavailable"));
  } else {
    float bowlBefore = feedStartWeight > 0.0f ? feedStartWeight : 0.0f;
    if (useSavedRemaining) {
      feedNeedGrams = feedTargetGrams - savedRemaining;
      feedStopAt = bowlBefore + feedNeedGrams;
      Serial.println("[Feeder] Saved previous Remaining " + String(savedRemaining, 1)
                     + " g -> add " + String(feedNeedGrams, 1) + " g");
    } else {
      feedNeedGrams = feedTargetGrams - bowlBefore;
      feedStopAt = feedTargetGrams;
    }
    Serial.println("[Feeder] Bowl before " + String(bowlBefore, 1) + " g, target "
                   + String(feedTargetGrams, 1) + " g, need " + String(feedNeedGrams, 1)
                   + " g, stopAt " + String(feedStopAt, 1) + " g");
    if (feedNeedGrams < DISPENSE_MIN_NEED_GRAMS) {
      feedBowlFull        = true;
      feedFinalizePending = true;
      feedFinalizeAtMs    = millis();
      Serial.println("[Feeder] Bowl already full - servo stays closed.");
      return;
    }
  }
  Serial.println("[Feeder] Dispensing food...");
  feederServo.write(SERVO_SWEEP_ANGLE);
  isFeeding      = true;
  feedingStartMs = millis();
  feedLastLogMs  = millis();
}

void updateFeeder() {
  if (isFeeding) {
    unsigned long elapsed = millis() - feedingStartMs;
    bool stop = false;

    if (feedWeightMode && scaleAvailable && hasValidLiveWeight()) {
      if (fastWeightGrams >= feedStopAt) {
        stop = true;
        Serial.println("[Feeder] Target reached: " + String(fastWeightGrams, 1) + " g >= stopAt "
                       + String(feedStopAt, 1) + " g after " + String(elapsed) + " ms");
      } else if (elapsed >= DISPENSE_MAX_MS) {
        stop = true;
        feedTimedOut = true;
        Serial.println("[Feeder] ANOMALY: " + String(DISPENSE_MAX_MS) + " ms open without reaching "
                       + String(feedStopAt, 1) + " g - hopper empty or jammed.");
      }
    } else {
      if (feedWeightMode) {
        feedWeightMode = false;
        Serial.println("[Feeder] Scale lost mid-dispense - TIMED fallback.");
      }
      if (elapsed >= SERVO_HOLD_MS) stop = true;
    }

    if (!stop && millis() - feedLastLogMs >= DISPENSE_LOG_MS) {
      feedLastLogMs = millis();
      Serial.println("[Feeder] Dispensing: weight " + String(fastWeightGrams, 1) + " g, stopAt "
                     + (feedWeightMode ? String(feedStopAt, 1) : String("n/a (timed)"))
                     + " g, elapsed " + String(elapsed) + " ms");
    }

    if (stop) {
      feederServo.write(SERVO_HOME_ANGLE);
      isFeeding = false;
      Serial.println("[Feeder] Servo returned home.");
      if (feedIsScheduled || feedRecordId.length() > 0) {
        feedFinalizePending = true;
        feedFinalizeAtMs    = millis() + FEED_SETTLE_MS;
      } else {
        Serial.println("[Record] Feed NOT tracked - started without a feeding record (e.g. /feed-now or no app command id).");
      }
    }
  }

  if (feedFinalizePending && (long)(millis() - feedFinalizeAtMs) >= 0) {
    if (!weightStableNow() && (long)(millis() - (feedFinalizeAtMs + BASELINE_STABLE_MAX_MS)) < 0) return;
    feedFinalizePending = false;
    if (feedIsScheduled) finalizeScheduledFeedRecord();
    else                 trackManualFeedRecord();
  }
}

volatile uint32_t waterPulseCount   = 0;
volatile uint32_t waterTargetPulses = 0;
volatile bool     waterActive       = false;
volatile bool     waterLimitReached = false;
uint32_t          waterLastPulses   = 0;
unsigned long     waterLastTickMs   = 0;
unsigned long     waterLastFlowMs   = 0;
float             waterTotalMl      = 0.0f;
float             waterFlowRateLpm  = 0.0f;
float             waterTargetMl     = 0.0f;
bool              waterFinalReported = true;
String            waterStopReason   = "";

volatile uint32_t waterLastPulseUs    = 0;
volatile uint32_t waterIgnoreUntilUs  = 0;
volatile uint32_t waterRejectedPulses = 0;

void ICACHE_RAM_ATTR waterPulseISR() {
  if (!waterActive) return;
  uint32_t nowUs = micros();
  // Start-up settling window: relay/pump switching noise.
  if ((int32_t)(nowUs - waterIgnoreUntilUs) < 0) { waterRejectedPulses++; return; }
  // Too soon after the previous pulse to be real flow.
  if ((uint32_t)(nowUs - waterLastPulseUs) < WATER_PULSE_MIN_US) { waterRejectedPulses++; return; }
  // A real pulse is still LOW when sampled; a short noise spike has already gone.
  if (digitalRead(PIN_FLOW_SENSOR) != LOW) { waterRejectedPulses++; return; }
  waterLastPulseUs = nowUs;
  waterPulseCount++;
  if (waterPulseCount >= waterTargetPulses) {
    digitalWrite(PIN_RELAY, RELAY_OFF_LEVEL);
    waterActive = false;
    waterLimitReached = true;
  }
}

void initWater() {
  digitalWrite(PIN_RELAY, RELAY_OFF_LEVEL);
  pinMode(PIN_RELAY, OUTPUT);
  pinMode(PIN_FLOW_SENSOR, INPUT_PULLUP);
  attachInterrupt(digitalPinToInterrupt(PIN_FLOW_SENSOR), waterPulseISR, FALLING);
  Serial.println("[Water] Ready (relay OFF). Waiting for a dispense command.");
}

bool isWaterDispensing() { return waterActive; }

bool startWater(float ml) {
  if (waterActive) return false;
  float maxMl = WATER_MAX_LITERS * 1000.0f;
  if (!(ml > 0.0f)) return false;
  if (ml > maxMl) ml = maxMl;
  uint32_t target = (uint32_t)(ml * WATER_PULSES_PER_LITER / 1000.0f + 0.5f);
  if (target == 0) target = 1;
  noInterrupts();
  waterPulseCount   = 0;
  waterTargetPulses = target;
  waterLimitReached = false;
  interrupts();
  waterLastPulses    = 0;
  waterTotalMl       = 0.0f;
  waterFlowRateLpm   = 0.0f;
  waterTargetMl      = ml;
  waterFinalReported = false;
  waterStopReason    = "";
  waterLastTickMs    = millis();
  waterLastFlowMs    = waterLastTickMs;
  noInterrupts();
  waterRejectedPulses = 0;
  waterLastPulseUs    = micros() - WATER_PULSE_MIN_US;
  waterIgnoreUntilUs  = micros() + WATER_SETTLE_MS * 1000UL;
  waterActive = true;
  interrupts();
  digitalWrite(PIN_RELAY, RELAY_ON_LEVEL);
  Serial.println("[Water] Relay ON. Target " + String(ml, 0) + " mL ("
                 + String((unsigned long)target) + " pulses).");
  return true;
}

void updateWater() {
  if (waterFinalReported) return;

  if (waterLimitReached) {
    noInterrupts();
    uint32_t pulses = waterPulseCount;
    interrupts();
    waterTotalMl     = pulses * 1000.0f / WATER_PULSES_PER_LITER;
    waterFlowRateLpm = 0.0f;
    waterFinalReported = true;
    Serial.println("[Water] Target reached - relay OFF. Final volume: "
                   + String(waterTotalMl, 1) + " mL (" + String(waterTotalMl / 1000.0f, 3)
                   + " L), pulses: " + String((unsigned long)pulses)
                   + ", rejected noise pulses: " + String((unsigned long)waterRejectedPulses));
    return;
  }

  unsigned long nowMs = millis();
  unsigned long dtMs  = nowMs - waterLastTickMs;
  if (dtMs < WATER_REPORT_INTERVAL_MS) return;
  waterLastTickMs = nowMs;

  noInterrupts();
  uint32_t pulses = waterPulseCount;
  interrupts();
  uint32_t delta = pulses - waterLastPulses;
  waterLastPulses = pulses;
  if (delta > 0) waterLastFlowMs = nowMs;

  float freqHz = delta * 1000.0f / (float)dtMs;
  waterFlowRateLpm = freqHz / FLOW_CALIBRATION_FACTOR;
  waterTotalMl     = pulses * 1000.0f / WATER_PULSES_PER_LITER;

  Serial.println("[Water] Flow: " + String(waterFlowRateLpm, 2) + " L/min, total: "
                 + String(waterTotalMl, 1) + " mL, pulses: " + String((unsigned long)pulses)
                 + ", rejected: " + String((unsigned long)waterRejectedPulses));

  if (nowMs - waterLastFlowMs >= WATER_NO_FLOW_TIMEOUT_MS) {
    noInterrupts();
    waterActive = false;
    digitalWrite(PIN_RELAY, RELAY_OFF_LEVEL);
    interrupts();
    waterFinalReported = true;
    waterStopReason = "no_flow";
    Serial.println("[Water] ANOMALY: no flow for " + String(WATER_NO_FLOW_TIMEOUT_MS / 1000UL)
                   + " s - relay OFF (empty tank or blocked line). Final volume: "
                   + String(waterTotalMl, 1) + " mL, pulses: " + String((unsigned long)pulses));
  }
}

const char* trayErrorText(int code) {
  switch (code) {
    case TRAY_ERR_BUSY:     return "Tray cycle already in progress";
    case TRAY_ERR_COOLDOWN: return "Tray is cooling down, try again in a moment";
    case TRAY_ERR_FEEDING:  return "Food dispensing in progress";
    case TRAY_ERR_NOT_OPEN: return "Tray is already closed";
    default:                return "ok";
  }
}

const char* trayStateName() {
  switch (trayState) {
    case TRAY_OPENING:  return "opening";
    case TRAY_HOLDING:  return "open";
    case TRAY_CLOSING:  return "closing";
    case TRAY_STOPPING: return "stopping";
    default:            return "closed";
  }
}

void initTrayPinSafe() {
  digitalWrite(PIN_TRAY, LOW);
  pinMode(PIN_TRAY, OUTPUT);
}

void initTray() {
  trayState = TRAY_IDLE;
  Serial.println("[Tray] Ready on D8 (closed, signal LOW).");
}

void releaseTrayPin() {
  trayServo.detach();
  digitalWrite(PIN_TRAY, LOW);
  pinMode(PIN_TRAY, OUTPUT);
}

static void beginTrayClose(unsigned long closeMs) {
  trayCloseDurationMs = closeMs;
  trayServo.writeMicroseconds(TRAY_CLOSE_US);
  trayState        = TRAY_CLOSING;
  trayPhaseStartMs = millis();
  Serial.println("[Tray] Closing: turning back for " + String(closeMs) + " ms.");
}

int startTray() {
  if (trayState != TRAY_IDLE) return TRAY_ERR_BUSY;
  if (trayHasRun && millis() - trayLastEndMs < TRAY_COOLDOWN_MS) return TRAY_ERR_COOLDOWN;
  if (isFeeding) return TRAY_ERR_FEEDING;
  trayServo.attach(PIN_TRAY);
  trayServo.writeMicroseconds(TRAY_OPEN_US);
  trayState        = TRAY_OPENING;
  trayPhaseStartMs = millis();
  trayOpenedMs     = 0;
  Serial.println("[Tray] Opening for " + String(TRAY_OPEN_MS) + " ms, hold " + String(TRAY_HOLD_MS)
                 + " ms, return " + String(TRAY_CLOSE_MS) + " ms.");
  return TRAY_OK;
}

int requestTrayClose() {
  if (trayState == TRAY_IDLE) return TRAY_ERR_NOT_OPEN;
  if (trayState != TRAY_OPENING && trayState != TRAY_HOLDING) return TRAY_ERR_BUSY;
  unsigned long opened = (trayState == TRAY_OPENING) ? (millis() - trayPhaseStartMs) : trayOpenedMs;
  if (opened > TRAY_OPEN_MS) opened = TRAY_OPEN_MS;
  trayOpenedMs = opened;
  beginTrayClose((unsigned long)((uint64_t)TRAY_CLOSE_MS * opened / TRAY_OPEN_MS));
  return TRAY_OK;
}

void updateTray() {
  if (trayState == TRAY_IDLE) return;
  unsigned long now = millis();
  unsigned long elapsed = now - trayPhaseStartMs;
  switch (trayState) {
    case TRAY_OPENING:
      if (elapsed < TRAY_OPEN_MS) return;
      trayServo.writeMicroseconds(TRAY_NEUTRAL_US);
      trayOpenedMs     = TRAY_OPEN_MS;
      trayState        = TRAY_HOLDING;
      trayPhaseStartMs = now;
      Serial.println("[Tray] Open - holding.");
      return;
    case TRAY_HOLDING:
#if TRAY_AUTO_CLOSE
      if (elapsed < TRAY_HOLD_MS) return;
      beginTrayClose(TRAY_CLOSE_MS);
#endif
      return;
    case TRAY_CLOSING:
      if (elapsed < trayCloseDurationMs) return;
      trayServo.writeMicroseconds(TRAY_NEUTRAL_US);
      trayState        = TRAY_STOPPING;
      trayPhaseStartMs = now;
      Serial.println("[Tray] Returned - stopping.");
      return;
    case TRAY_STOPPING:
      if (elapsed < TRAY_SETTLE_MS) return;
      break;
  }
  releaseTrayPin();                       // detach only after the neutral command has settled
  trayState     = TRAY_IDLE;
  trayLastEndMs = now;
  trayHasRun    = true;
  Serial.println("[Tray] Cycle complete - tray closed, servo stopped.");
  if (trayRemoteCommandId.length() > 0) {
    if (trayRemoteCommandId == lastProcessedRemoteCommandId
        && WiFi.status() == WL_CONNECTED && deviceUid.length() > 0) {
      firebasePatch(userPath("/device_status/remote_command.json"),
          String("{\"status\":\"completed\",\"completedAtMs\":") + String(millis()) + "}");
    }
    trayRemoteCommandId = "";
  }
}

void initServoPinSafe() {
  digitalWrite(PIN_SERVO, HIGH);
  pinMode(PIN_SERVO, OUTPUT);
}

void setupScale() {
  Serial.println();
  Serial.println("======================================");
  Serial.println("   HX711 + 5KG LOAD CELL (g / kg)");
  Serial.println("======================================");

  scale.begin(HX711_DOUT, HX711_SCK);

  if (!scale.is_ready()) {
    Serial.println("ERROR!");
    Serial.println("HX711 is NOT responding.");
    Serial.println();
    Serial.println("Check:");
    Serial.println("1. VCC");
    Serial.println("2. GND");
    Serial.println("3. DT/DOUT");
    Serial.println("4. SCK");
    Serial.println("5. Load cell wires");
    Serial.println("[HX711] Weighing disabled; feeder continues without it.");
    scaleAvailable = false;
    return;
  }

  Serial.println("HX711 FOUND!");
  Serial.println();

  scale.set_scale(CALIBRATION_FACTOR);

  scale.tare(5);

  Serial.println();
  Serial.println("TARE COMPLETE! Scale should read 0 g / 0.000 kg");
  Serial.println("---------------------------------------------");
  Serial.println("Place an object on the load cell.");
  Serial.println("---------------------------------------------");
  Serial.println();

  scaleAvailable     = true;
  lastWeightSampleMs = millis();
}

void updateScale() {
  if (!scaleAvailable) {
    if (millis() - lastScaleRetryMs < SCALE_RETRY_MS) return;
    lastScaleRetryMs = millis();
    if (scale.is_ready()) {
      scale.set_scale(CALIBRATION_FACTOR);
      scale.tare(10);
      scaleAvailable     = true;
      weightSkipLogged   = false;
      lastWeightSampleMs = millis();
      Serial.println("[HX711] Scale came online, tared.");
    }
    return;
  }

  if (scale.is_ready()) {
    missedWeightSamples = 0;
    weightSamples[weightSampleIndex] = scale.get_units(1);
    weightSampleIndex = (weightSampleIndex + 1) % SAMPLES;
    if (weightSampleCount < SAMPLES) weightSampleCount++;
    lastWeightSampleMs = millis();

    int fastN = weightSampleCount < 3 ? weightSampleCount : 3;
    float fastSum = 0.0;
    for (int k = 1; k <= fastN; k++)
      fastSum += weightSamples[(weightSampleIndex - k + SAMPLES) % SAMPLES];
    fastWeightGrams = fastSum / fastN;

    float sum = 0.0;
    for (int i = 0; i < weightSampleCount; i++) sum += weightSamples[i];
    float grams = sum / weightSampleCount;

    if (fabs(grams) < ZERO_THRESHOLD) grams = 0.0;
    currentWeightGrams = grams;
  }

  if (millis() - lastWeightPrintMs < WEIGHT_PRINT_INTERVAL_MS) return;
  lastWeightPrintMs = millis();

  if (millis() - lastWeightSampleMs > WEIGHT_TIMEOUT_MS) {
    missedWeightSamples++;
    if (missedWeightSamples < 3) return;
    Serial.println("ERROR: HX711 disconnected!");
    scaleAvailable = false;
    weightSkipLogged = false;
    return;
  }

  float kilograms = currentWeightGrams / 1000.0;

  Serial.print("Weight: ");
  Serial.print(currentWeightGrams, 1);
  Serial.print(" g");
  Serial.print("   |   ");
  Serial.print(kilograms, 3);
  Serial.println(" kg");
}

String buildStatusJson() {
  reconcileModeWithWifi();
  StaticJsonDocument<1280> doc;
  bool wifiConnected = (WiFi.status() == WL_CONNECTED);
  float liveWeight = currentWeightGrams > 0.0f ? currentWeightGrams : 0.0f;
  doc["mode"]        = wifiConnected ? "station"
                     : (currentMode == MODE_CONNECTING) ? "connecting" : "ap";
  doc["connected"]   = wifiConnected;
  doc["configuring"] = (currentMode == MODE_CONNECTING);
  doc["ssid"]        = wifiConnected ? WiFi.SSID() : "";
  doc["target_ssid"] = savedSSID;
  doc["ip"]          = wifiConnected
                         ? WiFi.localIP().toString()
                         : String(AP_IP_ADDR);
  doc["sta_ip"]      = wifiConnected ? WiFi.localIP().toString() : "";
  doc["mac"]         = WiFi.macAddress();
  doc["ap_ssid"]     = AP_SSID;
  doc["rssi"]        = wifiConnected ? WiFi.RSSI() : 0;
  doc["wifi_status"] = (int)WiFi.status();
  doc["wifi_mode"]   = (int)WiFi.getMode();
  doc["disconnect_reason"] = lastDisconnectReason;
  if (provisioningError.length() > 0) doc["connection_error"] = provisioningError;
  doc["food_level"]  = analogRead(PIN_FOOD_SENSOR);
  doc["feeding"]     = isFeeding;
  doc["scaleAvailable"] = scaleAvailable;
  doc["currentWeightAvailable"] = hasValidLiveWeight();
  doc["currentWeight"] = liveWeight;
  doc["weightUpdatedAtMs"] = lastWeightSampleMs;
  doc["water_dispensing"] = isWaterDispensing();
  doc["tray_active"]      = (trayState != TRAY_IDLE);
  doc["tray_state"]       = trayStateName();
  doc["water_ml"]         = waterTotalMl;
  doc["schedule_count"] = localScheduleCount;
  doc["account_linked"] = (deviceUid.length() > 0);
  doc["account_hash"]   = deviceUid.length() ? hashString(deviceUid) : 0;
  doc["time_synced"] = isClockSynced();
  doc["local_hour"] = currentLocalHour;
  doc["local_minute"] = currentLocalMinute;
  doc["local_wday"] = currentLocalWday;

  String output;
  serializeJson(doc, output);
  return output;
}

void addCORSHeaders() {
  server.sendHeader("Access-Control-Allow-Origin",  "*");
  server.sendHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
  server.sendHeader("Access-Control-Allow-Headers", "Content-Type");
}

uint8_t repeatDaysToMask(JsonArray days) {
  uint8_t mask = 0;
  if (days.isNull()) return mask;
  for (JsonVariant day : days) {
    String label = day.as<String>();
    if      (label == "Mon") mask |= (1 << 0);
    else if (label == "Tue") mask |= (1 << 1);
    else if (label == "Wed") mask |= (1 << 2);
    else if (label == "Thu") mask |= (1 << 3);
    else if (label == "Fri") mask |= (1 << 4);
    else if (label == "Sat") mask |= (1 << 5);
    else if (label == "Sun") mask |= (1 << 6);
  }
  return mask;
}

void handleConfigure() {
  addCORSHeaders();

  if (server.method() == HTTP_OPTIONS) { server.send(204); return; }

  if (!server.hasArg("plain")) {
    server.send(400, "application/json", "{\"error\":\"No body received\"}");
    return;
  }

  String body = server.arg("plain");
  Serial.println("[/configure] Body: " + body);

  StaticJsonDocument<512> doc;
  if (deserializeJson(doc, body)) {
    server.send(400, "application/json", "{\"error\":\"Invalid JSON\"}");
    return;
  }

  if (!doc.containsKey("ssid") || !doc.containsKey("password") || !doc.containsKey("token")) {
    server.send(400, "application/json", "{\"error\":\"Missing ssid, password, or token\"}");
    return;
  }

  String newSSID = doc["ssid"].as<String>();
  String newPass = doc["password"].as<String>();
  String token   = doc["token"].as<String>();

  if (newSSID.length() == 0) {
    server.send(400, "application/json", "{\"error\":\"SSID cannot be empty\"}");
    return;
  }

  if (!isProvisionTokenValid(token)) {
    server.send(401, "application/json", "{\"error\":\"Invalid provisioning token\"}");
    return;
  }

  saveCredentials(newSSID, newPass);
  savedSSID     = newSSID;
  savedPassword = newPass;
  pendingProvisionSsid = newSSID;
  pendingProvisionPass = newPass;
  provisioningError = "";
  lastDisconnectReason = -1;
  wifiRetryBackoffMs = WIFI_RETRY_INITIAL_MS;
  nextWifiRetryMs = 0;
  stationConnectInProgress = false;

  if (doc.containsKey("uid")) {
    String newUid = doc["uid"].as<String>();
    if (isValidUid(newUid)) linkAccount(newUid);
  }

  beginStationConnect();

  StaticJsonDocument<256> resp;
  resp["status"]    = "connecting";
  resp["connected"] = false;
  resp["ssid"]      = newSSID;
  resp["message"]   = "Credentials saved; connecting in background";
  resp["mac"]       = WiFi.macAddress();
  String respStr;
  serializeJson(resp, respStr);
  server.send(202, "application/json", respStr);

  provisionToken = "";
  provisionTokenExpiryMs = 0;
  Serial.println("[/configure] Accepted credentials, connecting to " + newSSID);
}

void handleProvisioningSession() {
  addCORSHeaders();
  startProvisioningSession();
  StaticJsonDocument<192> resp;
  resp["status"] = "ok";
  resp["token"] = provisionToken;
  resp["expires_in_ms"] = PROVISION_TOKEN_TTL_MS;
  resp["ap_ssid"] = AP_SSID;
  String out;
  serializeJson(resp, out);
  server.send(200, "application/json", out);
}

void handleStatus() {
  addCORSHeaders();
  server.send(200, "application/json", buildStatusJson());
}

void handleResetWifi() {
  addCORSHeaders();

  if (server.method() == HTTP_OPTIONS) { server.send(204); return; }

  server.send(200, "application/json",
    "{\"status\":\"ok\",\"message\":\"Resetting WiFi — device rebooting to AP mode\"}");

  delay(300);
  clearCredentials();
  delay(200);
  ESP.restart();
}

void handleFeedNow() {
  addCORSHeaders();

  if (server.method() == HTTP_OPTIONS) { server.send(204); return; }

  if (isFeeding) {
    server.send(409, "application/json",
      "{\"error\":\"Feed cycle already in progress\"}");
    return;
  }

  triggerFeed();
  server.send(200, "application/json",
    "{\"status\":\"ok\",\"message\":\"Feed cycle started\"}");
}

void handleCommand() {
  addCORSHeaders();
  if (server.method() == HTTP_OPTIONS) { server.send(204); return; }

  if (!server.hasArg("plain")) {
    server.send(400, "application/json", "{\"error\":\"No body received\"}");
    return;
  }

  StaticJsonDocument<256> doc;
  if (deserializeJson(doc, server.arg("plain"))) {
    server.send(400, "application/json", "{\"error\":\"Invalid JSON\"}");
    return;
  }

  String command = doc["command"] | "";
  if (command == "manual_feed_test") {
    if (isFeeding) {
      server.send(409, "application/json", "{\"error\":\"Feed cycle already in progress\"}");
      return;
    }
    float portion = doc["portionGrams"] | 0.0f;
    String commandId = doc["commandId"] | "";
    if (commandId.length() > 0 && deviceUid.length() > 0) feedManualRecordId = commandId;
    triggerFeed(portion);
    server.send(200, "application/json",
      "{\"status\":\"ok\",\"message\":\"Manual feed test accepted\",\"led_flashes\":5}");
    return;
  }

  if (command == "dispense_water") {
    float ml = doc["volumeMl"] | 0.0f;
    if (!(ml > 0.0f) || ml > WATER_MAX_LITERS * 1000.0f) {
      server.send(400, "application/json", "{\"error\":\"volumeMl must be between 1 and 5000\"}");
      return;
    }
    if (!startWater(ml)) {
      server.send(409, "application/json", "{\"error\":\"Water dispensing already in progress\"}");
      return;
    }
    server.send(200, "application/json",
      "{\"status\":\"ok\",\"message\":\"Water dispensing started\"}");
    return;
  }

  if (command == "open_tray" || command == "close_tray") {
    bool opening = (command == "open_tray");
    String commandId = doc["commandId"] | "";
    if (commandId.length() > 0 && commandId == trayLastCommandId) {
      server.send(200, "application/json",
        String("{\"status\":\"ok\",\"duplicate\":true,\"state\":\"") + trayStateName() + "\"}");
      return;
    }
    int rc = opening ? startTray() : requestTrayClose();
    if (rc != TRAY_OK) {
      int httpCode = (rc == TRAY_ERR_COOLDOWN) ? 429 : 409;
      server.send(httpCode, "application/json",
        String("{\"error\":\"") + trayErrorText(rc) + "\",\"state\":\"" + trayStateName() + "\"}");
      return;
    }
    if (commandId.length() > 0) trayLastCommandId = commandId;
    trayRemoteCommandId = "";
    server.send(200, "application/json",
      String("{\"status\":\"ok\",\"message\":\"") + (opening ? "Tray opening" : "Tray closing")
      + "\",\"state\":\"" + trayStateName() + "\""
      + ",\"openMs\":" + String(TRAY_OPEN_MS) + ",\"holdMs\":" + String(TRAY_HOLD_MS)
      + ",\"closeMs\":" + String(trayCloseDurationMs > 0 && !opening ? trayCloseDurationMs : TRAY_CLOSE_MS)
      + ",\"settleMs\":" + String(TRAY_SETTLE_MS) + "}");
    return;
  }

  server.send(400, "application/json", "{\"error\":\"Unknown command\"}");
}

void handleSchedules() {
  addCORSHeaders();
  if (server.method() == HTTP_OPTIONS) { server.send(204); return; }
  if (server.method() == HTTP_GET) {
    server.send(200, "application/json", buildSchedulesJson());
    return;
  }

  if (!server.hasArg("plain")) {
    server.send(400, "application/json", "{\"error\":\"No body received\"}");
    return;
  }

  DynamicJsonDocument doc(4096);
  if (deserializeJson(doc, server.arg("plain"))) {
    server.send(400, "application/json", "{\"error\":\"Invalid JSON\"}");
    return;
  }

  JsonArray schedules = doc["schedules"].as<JsonArray>();
  if (schedules.isNull()) {
    server.send(400, "application/json", "{\"error\":\"Missing schedules array\"}");
    return;
  }

  long nowEpochSeconds = doc["nowEpochSeconds"] | 0;
  if (nowEpochSeconds > 1609459200L) {
    timeval tv = { nowEpochSeconds, 0 };
    settimeofday(&tv, nullptr);
    Serial.println("[Time] Clock set from app schedule sync.");
  }

  applySchedulesFromJson(schedules);

  saveSchedulesToEEPROM();

  StaticJsonDocument<128> resp;
  resp["status"] = "ok";
  resp["saved"] = localScheduleCount;
  String out;
  serializeJson(resp, out);
  server.send(200, "application/json", out);
}

void handleRoot() {
  String modeLabel = (currentMode == MODE_STATION) ? "Connected" : "Setup Mode";
  String currentIp = (currentMode == MODE_STATION) ? WiFi.localIP().toString() : String(AP_IP_ADDR);
  String currentSsid = (currentMode == MODE_STATION) ? WiFi.SSID() : String(AP_SSID);

  String html = "<!DOCTYPE html><html><head>"
    "<meta charset='utf-8'>"
    "<meta name='viewport' content='width=device-width,initial-scale=1'>"
    "<title>Smart Pet Feeder Setup</title>"
    "<style>"
    ":root{--bg:#F8F3EE;--surface:#FFFFFF;--card:#FFFFFF;--accent:#5C3317;--accent2:#D4731A;--text:#2C1810;--muted:#6B4A35;--ok:#2D6A4F;--err:#9B2335;--line:#EAE0D8;--stroke:#DDD0C4;}"
    "*{box-sizing:border-box}body{margin:0;background:linear-gradient(180deg,#F8F3EE 0%,#F0E8DF 100%);min-height:100vh;font-family:Segoe UI,Roboto,Arial,sans-serif;color:var(--text)}"
    ".wrap{max-width:460px;margin:0 auto;padding:22px 16px 28px}"
    ".hero{background:linear-gradient(160deg,#3A1F0B,#5C3317 55%,#8B5E3C);border-radius:18px;padding:18px 16px;color:#fff;box-shadow:0 8px 24px rgba(92,51,23,.22)}"
    ".hero h1{margin:0;font-size:20px;letter-spacing:.2px;color:#fff}.hero p{margin:8px 0 0;color:#F5C27A;font-size:13px}"
    ".card{margin-top:14px;background:#FFFFFF;border:1px solid var(--stroke);border-radius:16px;padding:16px;box-shadow:0 2px 10px rgba(92,51,23,.08)}"
    ".label{font-size:11px;color:#8B5E3C;font-weight:700;text-transform:uppercase;letter-spacing:1.2px;margin:0 0 10px}"
    ".row{display:flex;justify-content:space-between;gap:10px;padding:8px 0;border-bottom:1px solid var(--line)}.row:last-child{border-bottom:none}"
    ".k{color:var(--muted);font-size:13px}.v{font-weight:600;font-size:13px;text-align:right}"
    ".status-chip{display:inline-block;padding:5px 10px;border-radius:999px;font-size:12px;font-weight:700;background:#E8F5EE;color:#2D6A4F;border:1px solid rgba(45,106,79,.35)}"
    ".status-chip.setup{background:#FFF3E0;color:#C45A1E;border-color:rgba(212,115,26,.45)}"
    "input{width:100%;height:46px;border-radius:12px;border:1px solid var(--stroke);background:#FDFAF7;color:var(--text);padding:0 14px;margin-bottom:10px;font-size:14px}"
    "input:focus{outline:none;border-color:var(--accent2);box-shadow:0 0 0 3px rgba(212,115,26,.18);background:#FFFFFF}"
    "button{width:100%;height:48px;border:none;border-radius:12px;background:linear-gradient(160deg,#8B5E3C,#5C3317);color:#fff;font-size:15px;font-weight:700;cursor:pointer}"
    ".btn-secondary{margin-top:10px;background:transparent;border:1px solid var(--err);color:var(--err)}"
    "button:disabled{opacity:.55;cursor:not-allowed}.hint{margin-top:10px;color:var(--muted);font-size:12px;line-height:1.45}"
    ".msg{margin-top:10px;padding:10px 12px;border-radius:10px;background:#FFF3E0;color:var(--text);border:1px solid #F5C27A;font-size:13px;min-height:38px;line-height:1.4}"
    ".ok{color:var(--ok)}.err{color:var(--err)}"
    "</style></head><body>"
    "<div class='wrap'>"
    "<div class='hero'><h1>Smart Pet Feeder</h1><p>WiFi Provisioning Portal</p></div>"
    "<div class='card'><p class='label'>Device Status</p>"
    "<div class='row'><span class='k'>Mode</span><span class='v'><span class='status-chip " + String(currentMode == MODE_STATION ? "" : "setup") + "'>" + modeLabel + "</span></span></div>"
    "<div class='row'><span class='k'>Device IP</span><span class='v'>" + currentIp + "</span></div>"
    "<div class='row'><span class='k'>SSID</span><span class='v'>" + currentSsid + "</span></div>"
    "</div>"
    "<div class='card'><p class='label'>Configure WiFi</p>"
    "<input id='ssid' type='text' placeholder='Home WiFi SSID' autocomplete='off'>"
    "<input id='pw' type='password' placeholder='WiFi Password (leave blank if open)' autocomplete='off'>"
    "<button id='btnCfg' onclick='cfg()'>Configure Device</button>"
    "<button class='btn-secondary' id='btnReset' onclick='resetWifi()'>Reset WiFi Credentials</button>"
    "<div id='msg' class='msg'>Ready to configure.</div>"
    "<div class='hint'>After success, reconnect your phone to your home WiFi and open the app.</div>"
    "</div></div>"
    "<script>"
    "async function cfg(){"
    " const btn=document.getElementById('btnCfg');"
    " const msg=document.getElementById('msg');"
    " const s=document.getElementById('ssid').value.trim();"
    " const p=document.getElementById('pw').value;"
    " if(!s){msg.className='msg err';msg.textContent='SSID is required.';return;}"
    " btn.disabled=true;msg.className='msg';msg.textContent='Starting provisioning session...';"
    " try{"
    "  const sessionRes=await fetch('/provisioning/session');"
    "  const session=await sessionRes.json();"
    "  if(!session.token) throw new Error(session.error||'Session token not available');"
    "  msg.textContent='Sending credentials and verifying connection (up to 15s)...';"
    "  const cfgRes=await fetch('/configure',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({ssid:s,password:p,token:session.token})});"
    "  const result=await cfgRes.json();"
    "  if(cfgRes.ok && result.connected){msg.className='msg ok';msg.textContent='Connected successfully. Device IP: '+result.ip;}"
    "  else{msg.className='msg err';msg.textContent='Failed: '+(result.error||'Unknown error');}"
    " }catch(e){msg.className='msg err';msg.textContent='Error: '+(e.message||e);}"
    " btn.disabled=false;"
    "}"
    "async function resetWifi(){"
    " const msg=document.getElementById('msg');"
    " if(!confirm('Reset saved WiFi credentials on feeder?')) return;"
    " document.getElementById('btnReset').disabled=true;"
    " msg.className='msg';msg.textContent='Resetting device...';"
    " try{"
    "  const r=await fetch('/reset-wifi',{method:'POST'});"
    "  const j=await r.json();"
    "  if(r.ok){msg.className='msg ok';msg.textContent='Reset sent. Device will reboot to setup mode.';}"
    "  else{msg.className='msg err';msg.textContent='Reset failed: '+(j.error||'Unknown error');}"
    " }catch(e){msg.className='msg err';msg.textContent='Reset error: '+(e.message||e);}"
    " setTimeout(()=>{document.getElementById('btnReset').disabled=false;},1200);"
    "}"
    "</script></body></html>";

  server.send(200, "text/html", html);
}

void handleLinkAccount() {
  addCORSHeaders();
  if (server.method() == HTTP_OPTIONS) { server.send(204); return; }

  if (!server.hasArg("plain")) {
    server.send(400, "application/json", "{\"error\":\"No body received\"}");
    return;
  }
  StaticJsonDocument<192> doc;
  if (deserializeJson(doc, server.arg("plain"))) {
    server.send(400, "application/json", "{\"error\":\"Invalid JSON\"}");
    return;
  }
  String uid = doc["uid"] | "";
  if (!isValidUid(uid)) {
    server.send(400, "application/json", "{\"error\":\"Invalid uid\"}");
    return;
  }
  linkAccount(uid);
  server.send(200, "application/json", "{\"status\":\"ok\",\"linked\":true}");
}

void sendCaptiveRedirect() {
  server.sendHeader("Location", String("http://") + WiFi.softAPIP().toString() + "/", true);
  server.sendHeader("Cache-Control", "no-cache, no-store, must-revalidate");
  server.send(302, "text/plain", "");
}

void handleNotFound() {
  if (isSetupApUp()) {
    String host = server.hostHeader();
    if (host.length() > 0 && host != WiFi.softAPIP().toString()) {
      sendCaptiveRedirect();
      return;
    }
  }
  server.send(404, "application/json", "{\"error\":\"Not found\"}");
}

void handleCaptiveProbe() {
  if (isSetupApUp()) sendCaptiveRedirect();
  else handleNotFound();
}

void setupWebServer() {
  server.on("/",           HTTP_GET,     handleRoot);
  server.on("/configure",  HTTP_POST,    handleConfigure);
  server.on("/configure",  HTTP_OPTIONS, handleConfigure);
  server.on("/provisioning/session", HTTP_GET, handleProvisioningSession);
  server.on("/status",     HTTP_GET,     handleStatus);
  server.on("/reset-wifi", HTTP_POST,    handleResetWifi);
  server.on("/reset-wifi", HTTP_OPTIONS, handleResetWifi);
  server.on("/feed-now",   HTTP_POST,    handleFeedNow);
  server.on("/feed-now",   HTTP_OPTIONS, handleFeedNow);
  server.on("/command",    HTTP_POST,    handleCommand);
  server.on("/command",    HTTP_OPTIONS, handleCommand);
  server.on("/link-account", HTTP_POST,    handleLinkAccount);
  server.on("/link-account", HTTP_OPTIONS, handleLinkAccount);
  server.on("/schedules",  HTTP_POST,    handleSchedules);
  server.on("/schedules",  HTTP_GET,     handleSchedules);
  server.on("/schedules",  HTTP_OPTIONS, handleSchedules);
  server.on("/generate_204",              HTTP_ANY, handleCaptiveProbe);
  server.on("/gen_204",                   HTTP_ANY, handleCaptiveProbe);
  server.on("/hotspot-detect.html",       HTTP_ANY, handleCaptiveProbe);
  server.on("/library/test/success.html", HTTP_ANY, handleCaptiveProbe);
  server.on("/connecttest.txt",           HTTP_ANY, handleCaptiveProbe);
  server.on("/ncsi.txt",                  HTTP_ANY, handleCaptiveProbe);
  server.on("/redirect",                  HTTP_ANY, handleCaptiveProbe);
  server.on("/fwlink",                    HTTP_ANY, handleCaptiveProbe);
  server.on("/canonical.html",            HTTP_ANY, handleCaptiveProbe);
  server.on("/success.txt",               HTTP_ANY, handleCaptiveProbe);
  server.onNotFound(handleNotFound);
  server.begin();
  Serial.println("[HTTP] Server started on port 80");
}

void setup() {
  Serial.begin(115200);
  delay(100);
  Serial.println("\n\n[Boot] Smart Pet Feeder starting...");

  initTrayPinSafe();
  initWater();
  initServoPinSafe();
  feederServo.attach(PIN_SERVO);
  feederServo.write(SERVO_HOME_ANGLE);
  Serial.println("[Boot] Positional servo attached at home.");
  initTray();

  setenv("TZ", "PHT-8", 1);
  tzset();

  EEPROM.begin(EEPROM_SIZE);
  loadSchedulesFromEEPROM();
  loadAccountLink();

  WiFi.persistent(false);
  WiFi.setAutoConnect(false);
  WiFi.setAutoReconnect(true);
  stationDisconnectedHandler = WiFi.onStationModeDisconnected(onStationDisconnected);
  stationGotIpHandler = WiFi.onStationModeGotIP(onStationGotIp);

  if (loadCredentials()) {
    beginStationConnect();
  } else {
    startAPMode();
  }

  setupWebServer();

  setupScale();
  Serial.println("[Boot] Initialisation complete.");
}

void loop() {
  reconcileModeWithWifi();

  if (currentMode == MODE_CONNECTING && !finalizeStationSwitchPending) {
    tryCompleteConnection();
  }
  updateWifiRetry();

  if (currentMode == MODE_STATION) {
    MDNS.update();
  }

  updateCaptiveDns();
  server.handleClient();
  updateTray();
  updateScale();
  updateFeeder();
  updateScheduledFeeding();
  updateTray();
  updateFirebaseRemoteControl();
  updateEatCheck();
  updateWater();

  if (currentMode == MODE_STATION && WiFi.status() != WL_CONNECTED) {
    Serial.println("[WiFi] Connection lost — attempting reconnect...");
    beginStationConnect();
  }

  if (finalizeStationSwitchPending && (long)(millis() - finalizeStationSwitchAtMs) >= 0) {
    finalizeStationSwitchPending = false;
    markStationConnected();
  }

  yield();
}
