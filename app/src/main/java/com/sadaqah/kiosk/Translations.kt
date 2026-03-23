package com.sadaqah.kiosk

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class Language(val code: String, val flag: String, val shortCode: String) {
    ENGLISH("en", "🇬🇧", "EN"),
    DUTCH(  "nl", "🇳🇱", "NL"),
    GERMAN( "de", "🇩🇪", "DE"),
    FRENCH( "fr", "🇫🇷", "FR"),
    SPANISH("es", "🇪🇸", "ES"),
    ITALIAN("it", "🇮🇹", "IT"),
    TURKISH("tr", "🇹🇷", "TR"),
    ARABIC( "ar", "🇸🇦", "AR"),
}

object TranslationManager {
    private val _currentLanguage = MutableStateFlow(Language.DUTCH)
    val currentLanguage: StateFlow<Language> = _currentLanguage

    fun setLanguage(language: Language) {
        _currentLanguage.value = language
    }

    fun fromCode(code: String): Language =
        Language.entries.firstOrNull { it.code == code } ?: Language.ENGLISH

    fun currentStrings(): Strings = when (_currentLanguage.value) {
        Language.DUTCH   -> DutchStrings
        Language.ENGLISH -> EnglishStrings
        Language.GERMAN  -> GermanStrings
        Language.FRENCH  -> FrenchStrings
        Language.SPANISH -> SpanishStrings
        Language.ITALIAN -> ItalianStrings
        Language.TURKISH -> TurkishStrings
        Language.ARABIC  -> ArabicStrings
    }
}

@Composable
fun rememberStrings(): Strings {
    val language by TranslationManager.currentLanguage.collectAsState()
    return when (language) {
        Language.DUTCH   -> DutchStrings
        Language.ENGLISH -> EnglishStrings
        Language.GERMAN  -> GermanStrings
        Language.FRENCH  -> FrenchStrings
        Language.SPANISH -> SpanishStrings
        Language.ITALIAN -> ItalianStrings
        Language.TURKISH -> TurkishStrings
        Language.ARABIC  -> ArabicStrings
    }
}

interface Strings {
    // Common
    val back: String
    val save: String
    val cancel: String
    val apply: String
    val yes: String
    val no: String

    // Login Screen
    val welcome: String
    val sumupAffiliateKey: String
    val logIn: String

    // Donation Screen
    val chooseAmount: String
    val customAmount: String
    val customAmountLine1: String
    val customAmountLine2: String

    // Custom Amount Screen
    val enterAmount: String
    val minMaxAmount: String
    val pay: String
    val minimumDonation: String
    val maximumDonation: String
    val enterValidAmount: String

    // Thank You Screen
    val thankYou: String          // Always Arabic blessing
    val thankYouLocalized: String // "Thank you" in this language

    // Maintenance Screen
    val maintenance: String
    val pleaseWait: String

    // Settings Screen
    val settings: String
    val kioskName: String
    val kioskNamePlaceholder: String
    val logoImage: String
    val selectLogo: String
    val colors: String
    val background: String
    val pattern: String
    val buttons: String
    val textBorder: String
    val exportSettings: String
    val importSettings: String
    val saveAndBack: String
    val resetApp: String
    val noLogoSelected: String
    val language: String
    val cardReader: String
    val connectCardReaderButton: String
    val loginFirstToConnect: String
    val experimental: String
    val thankYouToggleLabel: String
    val thankYouToggleDesc: String

    // Export/Import Dialogs
    val exportTitle: String
    val exportMessage: String
    val includeAffiliateKey: String
    val export: String
    val importTitle: String
    val importMessage: String
    val pasteJsonHere: String
    val import: String
    val settingsCopiedToClipboard: String
    val settingsImportedSuccessfully: String
    val failedToImportSettings: String
    val settingsExportedAndCopied: String
    val exportFailed: String
    val invalidJsonFormat: String
    val fileLoaded: String
    val fileIsEmpty: String
    val failedToReadFile: String
    val browseForJsonFile: String
    val orPasteJsonBelow: String
    val validJson: String
    val importing: String

    val tapToPayExperimental: String
    val useTapToPay: String

    // Color Picker
    val chooseColor: String
    val suggestedColors: String
    val recentColors: String

    // Errors & Messages
    val logInSuccessful: String
    val logInFailed: String
    val deviceConnectionSuccessful: String
    val connectionFailed: String
    val paymentSuccessful: String
    val paymentFailed: String
    val bluetoothDisabled: String
    val cardReaderTimeout: String
    val cardReaderNotFound: String
    val noConnection: String
    val transactionDeclined: String
    val noInternetConnection: String
    val notLoggedIn: String
    val locationRequired: String
    val affiliateKeySaved: String
    val reinitializing: String
    val reinitialized: String
    val tapAgainToReset: String
    val authenticationTimedOut: String
    val waitingForInternet: String

    // Screensaver
    val screensaver: String
    val previewNow: String
    val screensaverCustomMessage: String
    val screensaverCycleMessages: String
    val screensaverDefaultMessages: List<String>

    // Timers
    val timers: String
    val screensaverIdleTimeout: String
    val screensaverDuration: String
    val screensaverCustomMessageHold: String
    val screensaverMessageHold: String
    val thankYouDuration: String
    val minutes: String
    val seconds: String

    // Setup / Pinning
    val setupStatus: String
    val pinApp: String
    val unpinApp: String
    val reconnectWifi: String
    val configureWifi: String
    val enableBluetooth: String
    val disableBluetooth: String
    val testMode: String
    val testModeActive: String
    val logOut: String
    val logOutConfirmTitle: String
    val logOutConfirmMessage: String

    // Currency
    val currency: String
    val euro: String
    val usDollar: String
    val britishPound: String
}

// ── Dutch ─────────────────────────────────────────────────────────────────────
object DutchStrings : Strings {
    override val back = "Terug"
    override val save = "Opslaan"
    override val cancel = "Annuleren"
    override val apply = "Toepassen"
    override val yes = "Ja"
    override val no = "Nee"

    override val welcome = "WELKOM"
    override val sumupAffiliateKey = "SUMUP AFFILIATE KEY"
    override val logIn = "LOG IN"

    override val chooseAmount = "KIES UW BEDRAG"
    override val customAmount = "AANGEPAST BEDRAG"
    override val customAmountLine1 = "AANGEPAST"
    override val customAmountLine2 = "BEDRAG"

    override val enterAmount = "Voer bedrag in"
    override val minMaxAmount = "(€1 - €5000)"
    override val pay = "Betalen"
    override val minimumDonation = "Minimale donatie is €1"
    override val maximumDonation = "Maximale donatie is €5000"
    override val enterValidAmount = "Voer een geldig bedrag in"

    override val thankYou = "بارك الله\nفيك"
    override val thankYouLocalized = "Dank je wel"

    override val maintenance = "ONDERHOUD"
    override val pleaseWait = "Een moment geduld..."

    override val settings = "INSTELLINGEN"
    override val kioskName = "Kiosk Naam"
    override val kioskNamePlaceholder = "Bijv: Moskee Rotterdam"
    override val logoImage = "Logo Afbeelding"
    override val selectLogo = "Selecteer Logo"
    override val colors = "Kleuren"
    override val background = "Achtergrond"
    override val pattern = "Patroon"
    override val buttons = "Knoppen"
    override val textBorder = "Tekst/Rand"
    override val exportSettings = "Exporteren"
    override val importSettings = "Importeren"
    override val saveAndBack = "Opslaan & Terug"
    override val resetApp = "App Resetten"
    override val noLogoSelected = "Geen logo geselecteerd"
    override val language = "Taal"
    override val cardReader = "Kaartlezer"
    override val connectCardReaderButton = "Verbind Kaartlezer"
    override val loginFirstToConnect = "Log eerst in om de kaartlezer te verbinden"
    override val experimental = "Experimenteel"
    override val thankYouToggleLabel = "Dankboodschap"
    override val thankYouToggleDesc = "Islamitische zegenwens in het Arabisch tonen in plaats van vertaald dankjewel"

    override val exportTitle = "Instellingen Exporteren"
    override val exportMessage = "Exporteer uw instellingen naar een bestand?"
    override val includeAffiliateKey = "Inclusief Affiliate Key"
    override val export = "Exporteren"
    override val importTitle = "Instellingen Importeren"
    override val importMessage = "Plak uw geëxporteerde instellingen JSON:"
    override val pasteJsonHere = "Plak JSON hier"
    override val import = "Importeren"
    override val settingsCopiedToClipboard = "Instellingen gekopieerd naar klembord"
    override val settingsImportedSuccessfully = "Instellingen succesvol geïmporteerd"
    override val failedToImportSettings = "Importeren van instellingen mislukt"
    override val settingsExportedAndCopied = "Instellingen geëxporteerd en gekopieerd naar klembord"
    override val exportFailed = "Fout bij exporteren: "
    override val invalidJsonFormat = "Ongeldige JSON formaat"
    override val fileLoaded = "Bestand geladen: "
    override val fileIsEmpty = "Bestand is leeg"
    override val failedToReadFile = "Fout bij lezen bestand: "
    override val browseForJsonFile = "📁 Blader naar JSON bestand"
    override val orPasteJsonBelow = "Of plak JSON hieronder:"
    override val validJson = "Geldige JSON"
    override val importing = "Importeren..."

    override val tapToPayExperimental = "Experimenteel – mogelijk niet beschikbaar op alle apparaten"
    override val useTapToPay = "Gebruik Tap to Pay"

    override val chooseColor = "Kies Kleur"
    override val suggestedColors = "Aanbevolen"
    override val recentColors = "Recente kleuren"

    override val logInSuccessful = "Inloggen Geslaagd"
    override val logInFailed = "Inloggen Mislukt"
    override val deviceConnectionSuccessful = "Apparaat Verbinding Geslaagd"
    override val connectionFailed = "Verbinding Mislukt"
    override val paymentSuccessful = "Betaling Geslaagd"
    override val paymentFailed = "Betaling Mislukt"
    override val bluetoothDisabled = "Bluetooth is uitgeschakeld. Schakel Bluetooth in."
    override val cardReaderTimeout = "Kaartlezer timeout. Controleer of de lezer aan staat en in de buurt is."
    override val cardReaderNotFound = "Kaartlezer niet gevonden. Controleer of de lezer gekoppeld is."
    override val noConnection = "Geen verbinding met kaartlezer"
    override val transactionDeclined = "Transactie geweigerd"
    override val noInternetConnection = "Geen internetverbinding"
    override val notLoggedIn = "Niet ingelogd bij SumUp"
    override val locationRequired = "Locatie vereist"
    override val affiliateKeySaved = "Affiliate Key Opgeslagen"
    override val reinitializing = "Opnieuw initialiseren..."
    override val reinitialized = "SumUp opnieuw geïnitialiseerd"
    override val tapAgainToReset = "Tik nogmaals om te resetten"
    override val authenticationTimedOut = "Authenticatie verlopen"
    override val waitingForInternet = "Wachten op internetverbinding..."

    override val screensaver = "Schermbeveiliging"
    override val previewNow = "Nu Activeren"
    override val screensaverCustomMessage = "Persoonlijk bericht"
    override val screensaverCycleMessages = "Berichten wisselen"
    override val screensaverDefaultMessages = listOf(
        "Sadaqah Jariyah",
        "Steun ons nu",
        "Sadaqah vermeerder uw rijkdom",
        "Elke cent is een zaad van eindeloze beloning",
        "Wees vrijgevig",
        "Geef omwille van Allah",
        "جزاك الله خيرا"
    )
    override val timers = "Timers"
    override val screensaverIdleTimeout = "Schermbeveiliging na"
    override val screensaverDuration = "Duur schermbeveiliging"
    override val screensaverCustomMessageHold = "Persoonlijk bericht"
    override val screensaverMessageHold = "Berichten duur"
    override val thankYouDuration = "Bedankscherm"
    override val minutes = "min"
    override val seconds = "sec"

    override val setupStatus = "Installatiestatus"
    override val pinApp = "App Vastzetten"
    override val unpinApp = "App Losmaken"
    override val reconnectWifi = "WiFi Opnieuw Verbinden"
    override val configureWifi = "WiFi Configureren"
    override val enableBluetooth = "Bluetooth Inschakelen"
    override val disableBluetooth = "Bluetooth Uitschakelen"
    override val testMode = "Testmodus"
    override val testModeActive = "TESTMODUS"
    override val logOut = "Uitloggen"
    override val logOutConfirmTitle = "Uitloggen?"
    override val logOutConfirmMessage = "U wordt uitgelogd bij SumUp. U moet opnieuw inloggen om betalingen te verwerken."

    override val currency = "Valuta"
    override val euro = "Euro (€)"
    override val usDollar = "Amerikaanse Dollar ($)"
    override val britishPound = "Britse Pond (£)"
}

// ── English ───────────────────────────────────────────────────────────────────
object EnglishStrings : Strings {
    override val back = "Back"
    override val save = "Save"
    override val cancel = "Cancel"
    override val apply = "Apply"
    override val yes = "Yes"
    override val no = "No"

    override val welcome = "WELCOME"
    override val sumupAffiliateKey = "SUMUP AFFILIATE KEY"
    override val logIn = "LOG IN"

    override val chooseAmount = "CHOOSE YOUR AMOUNT"
    override val customAmount = "CUSTOM AMOUNT"
    override val customAmountLine1 = "CUSTOM"
    override val customAmountLine2 = "AMOUNT"

    override val enterAmount = "Enter amount"
    override val minMaxAmount = "(€1 - €5000)"
    override val pay = "Pay"
    override val minimumDonation = "Minimum donation is €1"
    override val maximumDonation = "Maximum donation is €5000"
    override val enterValidAmount = "Enter a valid amount"

    override val thankYou = "بارك الله\nفيك"
    override val thankYouLocalized = "Thank you"

    override val maintenance = "MAINTENANCE"
    override val pleaseWait = "Please wait..."

    override val settings = "SETTINGS"
    override val kioskName = "Kiosk Name"
    override val kioskNamePlaceholder = "E.g: Rotterdam Mosque"
    override val logoImage = "Logo Image"
    override val selectLogo = "Select Logo"
    override val colors = "Colors"
    override val background = "Background"
    override val pattern = "Pattern"
    override val buttons = "Buttons"
    override val textBorder = "Text/Border"
    override val exportSettings = "Export"
    override val importSettings = "Import"
    override val saveAndBack = "Save & Back"
    override val resetApp = "Reset App"
    override val noLogoSelected = "No logo selected"
    override val language = "Language"
    override val cardReader = "Card Reader"
    override val connectCardReaderButton = "Connect Card Reader"
    override val loginFirstToConnect = "Log in first to connect card reader"
    override val experimental = "Experimental"
    override val thankYouToggleLabel = "Thank You Message"
    override val thankYouToggleDesc = "Show Islamic blessing in Arabic instead of localized thank you"

    override val exportTitle = "Export Settings"
    override val exportMessage = "Export your settings to a file?"
    override val includeAffiliateKey = "Include Affiliate Key"
    override val export = "Export"
    override val importTitle = "Import Settings"
    override val importMessage = "Paste your exported settings JSON:"
    override val pasteJsonHere = "Paste JSON here"
    override val import = "Import"
    override val settingsCopiedToClipboard = "Settings copied to clipboard"
    override val settingsImportedSuccessfully = "Settings imported successfully"
    override val failedToImportSettings = "Failed to import settings"
    override val settingsExportedAndCopied = "Settings exported and copied to clipboard"
    override val exportFailed = "Export failed: "
    override val invalidJsonFormat = "Invalid JSON format"
    override val fileLoaded = "File loaded: "
    override val fileIsEmpty = "File is empty"
    override val failedToReadFile = "Failed to read file: "
    override val browseForJsonFile = "📁 Browse for JSON file"
    override val orPasteJsonBelow = "Or paste JSON below:"
    override val validJson = "Valid JSON"
    override val importing = "Importing..."

    override val tapToPayExperimental = "Experimental – may not be available on all devices"
    override val useTapToPay = "Use Tap to Pay"

    override val chooseColor = "Choose Color"
    override val suggestedColors = "Suggested"
    override val recentColors = "Recent"

    override val logInSuccessful = "Log In Successful"
    override val logInFailed = "Log In Failed"
    override val deviceConnectionSuccessful = "Device Connection Successful"
    override val connectionFailed = "Connection Failed"
    override val paymentSuccessful = "Payment Successful"
    override val paymentFailed = "Payment Failed"
    override val bluetoothDisabled = "Bluetooth is disabled. Please enable Bluetooth."
    override val cardReaderTimeout = "Card reader timeout. Please check if reader is on and nearby."
    override val cardReaderNotFound = "Card reader not found. Please check if reader is paired."
    override val noConnection = "No connection to card reader"
    override val transactionDeclined = "Transaction declined"
    override val noInternetConnection = "No internet connection"
    override val notLoggedIn = "Not logged in to SumUp"
    override val locationRequired = "Location required"
    override val affiliateKeySaved = "Affiliate Key Saved"
    override val reinitializing = "Reinitializing SumUp..."
    override val reinitialized = "SumUp reinitialized"
    override val tapAgainToReset = "Tap again to reset"
    override val authenticationTimedOut = "Authentication timed out"
    override val waitingForInternet = "Waiting for internet connection..."

    override val screensaver = "Screensaver"
    override val previewNow = "Preview Now"
    override val screensaverCustomMessage = "Custom Message"
    override val screensaverCycleMessages = "Cycle messages"
    override val screensaverDefaultMessages = listOf(
        "Sadaqah Jariyah",
        "Support us now",
        "Sadaqah grows your wealth",
        "Every penny is a seed of endless reward",
        "Be among the generous",
        "Give for the sake of Allah",
        "جزاك الله خيرا"
    )
    override val timers = "Timers"
    override val screensaverIdleTimeout = "Idle before screensaver"
    override val screensaverDuration = "Screensaver duration"
    override val screensaverCustomMessageHold = "Custom message hold"
    override val screensaverMessageHold = "Message hold"
    override val thankYouDuration = "Thank you screen"
    override val minutes = "min"
    override val seconds = "sec"

    override val setupStatus = "Setup Status"
    override val pinApp = "Pin App"
    override val unpinApp = "Unpin App"
    override val reconnectWifi = "Reconnect WiFi"
    override val configureWifi = "Configure WiFi"
    override val enableBluetooth = "Enable Bluetooth"
    override val disableBluetooth = "Disable Bluetooth"
    override val testMode = "Test Mode"
    override val testModeActive = "TEST MODE"
    override val logOut = "Log Out"
    override val logOutConfirmTitle = "Log out?"
    override val logOutConfirmMessage = "You will be logged out of SumUp. You will need to log in again to process payments."

    override val currency = "Currency"
    override val euro = "Euro (€)"
    override val usDollar = "US Dollar ($)"
    override val britishPound = "British Pound (£)"
}

// ── German ────────────────────────────────────────────────────────────────────
object GermanStrings : Strings {
    override val back = "Zurück"
    override val save = "Speichern"
    override val cancel = "Abbrechen"
    override val apply = "Anwenden"
    override val yes = "Ja"
    override val no = "Nein"

    override val welcome = "WILLKOMMEN"
    override val sumupAffiliateKey = "SUMUP AFFILIATE KEY"
    override val logIn = "ANMELDEN"

    override val chooseAmount = "BETRAG WÄHLEN"
    override val customAmount = "ANDERER BETRAG"
    override val customAmountLine1 = "ANDERER"
    override val customAmountLine2 = "BETRAG"

    override val enterAmount = "Betrag eingeben"
    override val minMaxAmount = "(€1 - €5000)"
    override val pay = "Bezahlen"
    override val minimumDonation = "Mindestspende ist €1"
    override val maximumDonation = "Höchstspende ist €5000"
    override val enterValidAmount = "Gültigen Betrag eingeben"

    override val thankYou = "بارك الله\nفيك"
    override val thankYouLocalized = "Danke schön"

    override val maintenance = "WARTUNG"
    override val pleaseWait = "Bitte warten..."

    override val settings = "EINSTELLUNGEN"
    override val kioskName = "Kiosk-Name"
    override val kioskNamePlaceholder = "Z.B: Moschee Hamburg"
    override val logoImage = "Logo Bild"
    override val selectLogo = "Logo Auswählen"
    override val colors = "Farben"
    override val background = "Hintergrund"
    override val pattern = "Muster"
    override val buttons = "Tasten"
    override val textBorder = "Text/Rand"
    override val exportSettings = "Exportieren"
    override val importSettings = "Importieren"
    override val saveAndBack = "Speichern & Zurück"
    override val resetApp = "App Zurücksetzen"
    override val noLogoSelected = "Kein Logo ausgewählt"
    override val language = "Sprache"
    override val cardReader = "Kartenleser"
    override val connectCardReaderButton = "Kartenleser Verbinden"
    override val loginFirstToConnect = "Zuerst anmelden um den Kartenleser zu verbinden"
    override val experimental = "Experimentell"
    override val thankYouToggleLabel = "Dankesnachricht"
    override val thankYouToggleDesc = "Islamischen arabischen Segen anzeigen statt übersetztem Dankeschön"

    override val exportTitle = "Einstellungen Exportieren"
    override val exportMessage = "Einstellungen in eine Datei exportieren?"
    override val includeAffiliateKey = "Affiliate Key einbeziehen"
    override val export = "Exportieren"
    override val importTitle = "Einstellungen Importieren"
    override val importMessage = "Exportierte JSON-Einstellungen einfügen:"
    override val pasteJsonHere = "JSON hier einfügen"
    override val import = "Importieren"
    override val settingsCopiedToClipboard = "Einstellungen in Zwischenablage kopiert"
    override val settingsImportedSuccessfully = "Einstellungen erfolgreich importiert"
    override val failedToImportSettings = "Import der Einstellungen fehlgeschlagen"
    override val settingsExportedAndCopied = "Einstellungen exportiert und in Zwischenablage kopiert"
    override val exportFailed = "Exportfehler: "
    override val invalidJsonFormat = "Ungültiges JSON-Format"
    override val fileLoaded = "Datei geladen: "
    override val fileIsEmpty = "Datei ist leer"
    override val failedToReadFile = "Fehler beim Lesen der Datei: "
    override val browseForJsonFile = "📁 JSON-Datei durchsuchen"
    override val orPasteJsonBelow = "Oder JSON unten einfügen:"
    override val validJson = "Gültige JSON"
    override val importing = "Importieren..."

    override val tapToPayExperimental = "Experimentell – möglicherweise nicht auf allen Geräten verfügbar"
    override val useTapToPay = "Tap to Pay verwenden"

    override val chooseColor = "Farbe Wählen"
    override val suggestedColors = "Vorgeschlagen"
    override val recentColors = "Zuletzt verwendet"

    override val logInSuccessful = "Anmeldung Erfolgreich"
    override val logInFailed = "Anmeldung Fehlgeschlagen"
    override val deviceConnectionSuccessful = "Gerät Verbunden"
    override val connectionFailed = "Verbindung Fehlgeschlagen"
    override val paymentSuccessful = "Zahlung Erfolgreich"
    override val paymentFailed = "Zahlung Fehlgeschlagen"
    override val bluetoothDisabled = "Bluetooth ist deaktiviert. Bitte Bluetooth aktivieren."
    override val cardReaderTimeout = "Kartenleser-Timeout. Bitte prüfen ob Leser eingeschaltet und in der Nähe ist."
    override val cardReaderNotFound = "Kartenleser nicht gefunden. Bitte prüfen ob Leser gekoppelt ist."
    override val noConnection = "Keine Verbindung zum Kartenleser"
    override val transactionDeclined = "Transaktion abgelehnt"
    override val noInternetConnection = "Keine Internetverbindung"
    override val notLoggedIn = "Nicht bei SumUp angemeldet"
    override val locationRequired = "Standort erforderlich"
    override val affiliateKeySaved = "Affiliate Key Gespeichert"
    override val reinitializing = "Wird neu initialisiert..."
    override val reinitialized = "SumUp neu initialisiert"
    override val tapAgainToReset = "Nochmals tippen zum Zurücksetzen"
    override val authenticationTimedOut = "Authentifizierung abgelaufen"
    override val waitingForInternet = "Warte auf Internetverbindung..."

    override val screensaver = "Bildschirmschoner"
    override val previewNow = "Jetzt Anzeigen"
    override val screensaverCustomMessage = "Persönliche Nachricht"
    override val screensaverCycleMessages = "Nachrichten wechseln"
    override val screensaverDefaultMessages = listOf(
        "Sadaqah Jariyah",
        "Unterstützen Sie uns jetzt",
        "Sadaqah mehrt Ihren Reichtum",
        "Jeder Cent ist ein Samen endloser Belohnung",
        "Seien Sie großzügig",
        "Geben Sie um Allahs willen",
        "جزاك الله خيرا"
    )
    override val timers = "Timer"
    override val screensaverIdleTimeout = "Leerlauf vor Bildschirmschoner"
    override val screensaverDuration = "Bildschirmschoner-Dauer"
    override val screensaverCustomMessageHold = "Eigene Nachricht"
    override val screensaverMessageHold = "Nachrichtendauer"
    override val thankYouDuration = "Dankeschirm"
    override val minutes = "Min"
    override val seconds = "Sek"

    override val setupStatus = "Einrichtungsstatus"
    override val pinApp = "App Fixieren"
    override val unpinApp = "App Lösen"
    override val reconnectWifi = "WLAN Wiederherstellen"
    override val configureWifi = "WLAN Konfigurieren"
    override val enableBluetooth = "Bluetooth Aktivieren"
    override val disableBluetooth = "Bluetooth Deaktivieren"
    override val testMode = "Testmodus"
    override val testModeActive = "TESTMODUS"
    override val logOut = "Abmelden"
    override val logOutConfirmTitle = "Abmelden?"
    override val logOutConfirmMessage = "Sie werden von SumUp abgemeldet. Sie müssen sich erneut anmelden, um Zahlungen zu verarbeiten."

    override val currency = "Währung"
    override val euro = "Euro (€)"
    override val usDollar = "US-Dollar ($)"
    override val britishPound = "Britisches Pfund (£)"
}

// ── French ────────────────────────────────────────────────────────────────────
object FrenchStrings : Strings {
    override val back = "Retour"
    override val save = "Sauvegarder"
    override val cancel = "Annuler"
    override val apply = "Appliquer"
    override val yes = "Oui"
    override val no = "Non"

    override val welcome = "BIENVENUE"
    override val sumupAffiliateKey = "CLÉ AFFILIÉ SUMUP"
    override val logIn = "SE CONNECTER"

    override val chooseAmount = "CHOISISSEZ VOTRE MONTANT"
    override val customAmount = "MONTANT PERSONNALISÉ"
    override val customAmountLine1 = "MONTANT"
    override val customAmountLine2 = "PERSONNALISÉ"

    override val enterAmount = "Entrer le montant"
    override val minMaxAmount = "(€1 - €5000)"
    override val pay = "Payer"
    override val minimumDonation = "Don minimum €1"
    override val maximumDonation = "Don maximum €5000"
    override val enterValidAmount = "Entrer un montant valide"

    override val thankYou = "بارك الله\nفيك"
    override val thankYouLocalized = "Merci"

    override val maintenance = "MAINTENANCE"
    override val pleaseWait = "Veuillez patienter..."

    override val settings = "PARAMÈTRES"
    override val kioskName = "Nom du Kiosque"
    override val kioskNamePlaceholder = "Ex: Mosquée Paris"
    override val logoImage = "Image Logo"
    override val selectLogo = "Sélectionner Logo"
    override val colors = "Couleurs"
    override val background = "Arrière-plan"
    override val pattern = "Motif"
    override val buttons = "Boutons"
    override val textBorder = "Texte/Bordure"
    override val exportSettings = "Exporter"
    override val importSettings = "Importer"
    override val saveAndBack = "Sauvegarder & Retour"
    override val resetApp = "Réinitialiser App"
    override val noLogoSelected = "Aucun logo sélectionné"
    override val language = "Langue"
    override val cardReader = "Lecteur de Carte"
    override val connectCardReaderButton = "Connecter Lecteur"
    override val loginFirstToConnect = "Connectez-vous d'abord pour connecter le lecteur"
    override val experimental = "Expérimental"
    override val thankYouToggleLabel = "Message de remerciement"
    override val thankYouToggleDesc = "Afficher la bénédiction islamique en arabe au lieu du merci traduit"

    override val exportTitle = "Exporter Paramètres"
    override val exportMessage = "Exporter vos paramètres dans un fichier?"
    override val includeAffiliateKey = "Inclure Clé Affilié"
    override val export = "Exporter"
    override val importTitle = "Importer Paramètres"
    override val importMessage = "Coller votre JSON de paramètres exportés:"
    override val pasteJsonHere = "Coller JSON ici"
    override val import = "Importer"
    override val settingsCopiedToClipboard = "Paramètres copiés dans le presse-papiers"
    override val settingsImportedSuccessfully = "Paramètres importés avec succès"
    override val failedToImportSettings = "Échec de l'importation des paramètres"
    override val settingsExportedAndCopied = "Paramètres exportés et copiés dans le presse-papiers"
    override val exportFailed = "Échec export: "
    override val invalidJsonFormat = "Format JSON invalide"
    override val fileLoaded = "Fichier chargé: "
    override val fileIsEmpty = "Fichier vide"
    override val failedToReadFile = "Erreur de lecture: "
    override val browseForJsonFile = "📁 Parcourir fichier JSON"
    override val orPasteJsonBelow = "Ou coller JSON ci-dessous:"
    override val validJson = "JSON valide"
    override val importing = "Importation..."

    override val tapToPayExperimental = "Expérimental – peut ne pas être disponible sur tous les appareils"
    override val useTapToPay = "Utiliser Tap to Pay"

    override val chooseColor = "Choisir Couleur"
    override val suggestedColors = "Suggérées"
    override val recentColors = "Récentes"

    override val logInSuccessful = "Connexion Réussie"
    override val logInFailed = "Connexion Échouée"
    override val deviceConnectionSuccessful = "Appareil Connecté"
    override val connectionFailed = "Connexion Échouée"
    override val paymentSuccessful = "Paiement Réussi"
    override val paymentFailed = "Paiement Échoué"
    override val bluetoothDisabled = "Bluetooth désactivé. Veuillez activer Bluetooth."
    override val cardReaderTimeout = "Délai lecteur. Vérifiez que le lecteur est allumé et à proximité."
    override val cardReaderNotFound = "Lecteur non trouvé. Vérifiez qu'il est couplé."
    override val noConnection = "Pas de connexion au lecteur"
    override val transactionDeclined = "Transaction refusée"
    override val noInternetConnection = "Pas de connexion internet"
    override val notLoggedIn = "Non connecté à SumUp"
    override val locationRequired = "Localisation requise"
    override val affiliateKeySaved = "Clé Affilié Sauvegardée"
    override val reinitializing = "Réinitialisation..."
    override val reinitialized = "SumUp réinitialisé"
    override val tapAgainToReset = "Toucher à nouveau pour réinitialiser"
    override val authenticationTimedOut = "Authentification expirée"
    override val waitingForInternet = "En attente de connexion internet..."

    override val screensaver = "Économiseur d'écran"
    override val previewNow = "Prévisualiser"
    override val screensaverCustomMessage = "Message personnalisé"
    override val screensaverCycleMessages = "Faire défiler les messages"
    override val screensaverDefaultMessages = listOf(
        "Sadaqah Jariyah",
        "Soutenez-nous maintenant",
        "La Sadaqah accroît votre richesse",
        "Chaque centime est une graine de récompense infinie",
        "Soyez parmi les généreux",
        "Donnez pour l'amour d'Allah",
        "جزاك الله خيرا"
    )
    override val timers = "Minuteries"
    override val screensaverIdleTimeout = "Inactivité avant économiseur"
    override val screensaverDuration = "Durée économiseur"
    override val screensaverCustomMessageHold = "Message personnalisé"
    override val screensaverMessageHold = "Durée message"
    override val thankYouDuration = "Écran de remerciement"
    override val minutes = "min"
    override val seconds = "sec"

    override val setupStatus = "État de configuration"
    override val pinApp = "Épingler l'App"
    override val unpinApp = "Désépingler"
    override val reconnectWifi = "Reconnecter WiFi"
    override val configureWifi = "Configurer WiFi"
    override val enableBluetooth = "Activer Bluetooth"
    override val disableBluetooth = "Désactiver Bluetooth"
    override val testMode = "Mode test"
    override val testModeActive = "MODE TEST"
    override val logOut = "Se déconnecter"
    override val logOutConfirmTitle = "Se déconnecter ?"
    override val logOutConfirmMessage = "Vous serez déconnecté de SumUp. Vous devrez vous reconnecter pour traiter les paiements."

    override val currency = "Devise"
    override val euro = "Euro (€)"
    override val usDollar = "Dollar américain ($)"
    override val britishPound = "Livre sterling (£)"
}

// ── Spanish ───────────────────────────────────────────────────────────────────
object SpanishStrings : Strings {
    override val back = "Atrás"
    override val save = "Guardar"
    override val cancel = "Cancelar"
    override val apply = "Aplicar"
    override val yes = "Sí"
    override val no = "No"

    override val welcome = "BIENVENIDO"
    override val sumupAffiliateKey = "CLAVE AFILIADO SUMUP"
    override val logIn = "INICIAR SESIÓN"

    override val chooseAmount = "ELIGE TU IMPORTE"
    override val customAmount = "IMPORTE PERSONALIZADO"
    override val customAmountLine1 = "IMPORTE"
    override val customAmountLine2 = "PERSONALIZADO"

    override val enterAmount = "Ingresa el importe"
    override val minMaxAmount = "(€1 - €5000)"
    override val pay = "Pagar"
    override val minimumDonation = "Donación mínima €1"
    override val maximumDonation = "Donación máxima €5000"
    override val enterValidAmount = "Ingresa un importe válido"

    override val thankYou = "بارك الله\nفيك"
    override val thankYouLocalized = "Gracias"

    override val maintenance = "MANTENIMIENTO"
    override val pleaseWait = "Por favor espere..."

    override val settings = "CONFIGURACIÓN"
    override val kioskName = "Nombre del Kiosco"
    override val kioskNamePlaceholder = "Ej: Mezquita Madrid"
    override val logoImage = "Imagen Logo"
    override val selectLogo = "Seleccionar Logo"
    override val colors = "Colores"
    override val background = "Fondo"
    override val pattern = "Patrón"
    override val buttons = "Botones"
    override val textBorder = "Texto/Borde"
    override val exportSettings = "Exportar"
    override val importSettings = "Importar"
    override val saveAndBack = "Guardar & Volver"
    override val resetApp = "Restablecer App"
    override val noLogoSelected = "Sin logo seleccionado"
    override val language = "Idioma"
    override val cardReader = "Lector de Tarjeta"
    override val connectCardReaderButton = "Conectar Lector"
    override val loginFirstToConnect = "Inicia sesión para conectar el lector"
    override val experimental = "Experimental"
    override val thankYouToggleLabel = "Mensaje de agradecimiento"
    override val thankYouToggleDesc = "Mostrar bendición islámica en árabe en lugar de gracias traducido"

    override val exportTitle = "Exportar Configuración"
    override val exportMessage = "¿Exportar configuración a un archivo?"
    override val includeAffiliateKey = "Incluir Clave Afiliado"
    override val export = "Exportar"
    override val importTitle = "Importar Configuración"
    override val importMessage = "Pega tu JSON de configuración exportado:"
    override val pasteJsonHere = "Pegar JSON aquí"
    override val import = "Importar"
    override val settingsCopiedToClipboard = "Configuración copiada al portapapeles"
    override val settingsImportedSuccessfully = "Configuración importada con éxito"
    override val failedToImportSettings = "Error al importar configuración"
    override val settingsExportedAndCopied = "Configuración exportada y copiada al portapapeles"
    override val exportFailed = "Error de exportación: "
    override val invalidJsonFormat = "Formato JSON inválido"
    override val fileLoaded = "Archivo cargado: "
    override val fileIsEmpty = "Archivo vacío"
    override val failedToReadFile = "Error al leer: "
    override val browseForJsonFile = "📁 Buscar archivo JSON"
    override val orPasteJsonBelow = "O pegar JSON aquí:"
    override val validJson = "JSON válido"
    override val importing = "Importando..."

    override val tapToPayExperimental = "Experimental – puede no estar disponible en todos los dispositivos"
    override val useTapToPay = "Usar Tap to Pay"

    override val chooseColor = "Elegir Color"
    override val suggestedColors = "Sugeridos"
    override val recentColors = "Recientes"

    override val logInSuccessful = "Inicio de Sesión Exitoso"
    override val logInFailed = "Inicio de Sesión Fallido"
    override val deviceConnectionSuccessful = "Dispositivo Conectado"
    override val connectionFailed = "Conexión Fallida"
    override val paymentSuccessful = "Pago Exitoso"
    override val paymentFailed = "Pago Fallido"
    override val bluetoothDisabled = "Bluetooth desactivado. Por favor activa Bluetooth."
    override val cardReaderTimeout = "Tiempo de espera del lector. Verifica que esté encendido y cerca."
    override val cardReaderNotFound = "Lector no encontrado. Verifica que esté vinculado."
    override val noConnection = "Sin conexión al lector"
    override val transactionDeclined = "Transacción rechazada"
    override val noInternetConnection = "Sin conexión a internet"
    override val notLoggedIn = "No conectado a SumUp"
    override val locationRequired = "Ubicación requerida"
    override val affiliateKeySaved = "Clave Afiliado Guardada"
    override val reinitializing = "Reiniciando..."
    override val reinitialized = "SumUp reiniciado"
    override val tapAgainToReset = "Toca de nuevo para restablecer"
    override val authenticationTimedOut = "Autenticación expirada"
    override val waitingForInternet = "Esperando conexión a internet..."

    override val screensaver = "Protector de pantalla"
    override val previewNow = "Vista Previa"
    override val screensaverCustomMessage = "Mensaje personalizado"
    override val screensaverCycleMessages = "Alternar mensajes"
    override val screensaverDefaultMessages = listOf(
        "Sadaqah Jariyah",
        "Apóyanos ahora",
        "La Sadaqah aumenta tu riqueza",
        "Cada centavo es una semilla de recompensa infinita",
        "Sé generoso",
        "Da por el amor de Allah",
        "جزاك الله خيرا"
    )
    override val timers = "Temporizadores"
    override val screensaverIdleTimeout = "Inactividad antes del protector"
    override val screensaverDuration = "Duración del protector"
    override val screensaverCustomMessageHold = "Mensaje personalizado"
    override val screensaverMessageHold = "Duración mensaje"
    override val thankYouDuration = "Pantalla de gracias"
    override val minutes = "min"
    override val seconds = "seg"

    override val setupStatus = "Estado de configuración"
    override val pinApp = "Fijar App"
    override val unpinApp = "Desanclar App"
    override val reconnectWifi = "Reconectar WiFi"
    override val configureWifi = "Configurar WiFi"
    override val enableBluetooth = "Activar Bluetooth"
    override val disableBluetooth = "Desactivar Bluetooth"
    override val testMode = "Modo de prueba"
    override val testModeActive = "MODO PRUEBA"
    override val logOut = "Cerrar sesión"
    override val logOutConfirmTitle = "¿Cerrar sesión?"
    override val logOutConfirmMessage = "Se cerrará su sesión en SumUp. Deberá iniciar sesión de nuevo para procesar pagos."

    override val currency = "Moneda"
    override val euro = "Euro (€)"
    override val usDollar = "Dólar estadounidense ($)"
    override val britishPound = "Libra esterlina (£)"
}

// ── Italian ───────────────────────────────────────────────────────────────────
object ItalianStrings : Strings {
    override val back = "Indietro"
    override val save = "Salva"
    override val cancel = "Annulla"
    override val apply = "Applica"
    override val yes = "Sì"
    override val no = "No"

    override val welcome = "BENVENUTO"
    override val sumupAffiliateKey = "CHIAVE AFFILIATO SUMUP"
    override val logIn = "ACCEDI"

    override val chooseAmount = "SCEGLI IL TUO IMPORTO"
    override val customAmount = "IMPORTO PERSONALIZZATO"
    override val customAmountLine1 = "IMPORTO"
    override val customAmountLine2 = "PERSONALIZZATO"

    override val enterAmount = "Inserisci importo"
    override val minMaxAmount = "(€1 - €5000)"
    override val pay = "Paga"
    override val minimumDonation = "Donazione minima €1"
    override val maximumDonation = "Donazione massima €5000"
    override val enterValidAmount = "Inserisci un importo valido"

    override val thankYou = "بارك الله\nفيك"
    override val thankYouLocalized = "Grazie"

    override val maintenance = "MANUTENZIONE"
    override val pleaseWait = "Attendere prego..."

    override val settings = "IMPOSTAZIONI"
    override val kioskName = "Nome Kiosk"
    override val kioskNamePlaceholder = "Es: Moschea Milano"
    override val logoImage = "Immagine Logo"
    override val selectLogo = "Seleziona Logo"
    override val colors = "Colori"
    override val background = "Sfondo"
    override val pattern = "Motivo"
    override val buttons = "Pulsanti"
    override val textBorder = "Testo/Bordo"
    override val exportSettings = "Esporta"
    override val importSettings = "Importa"
    override val saveAndBack = "Salva & Indietro"
    override val resetApp = "Reimposta App"
    override val noLogoSelected = "Nessun logo selezionato"
    override val language = "Lingua"
    override val cardReader = "Lettore di Carte"
    override val connectCardReaderButton = "Connetti Lettore"
    override val loginFirstToConnect = "Accedi prima di connettere il lettore"
    override val experimental = "Sperimentale"
    override val thankYouToggleLabel = "Messaggio di ringraziamento"
    override val thankYouToggleDesc = "Mostra benedizione islamica in arabo invece del grazie tradotto"

    override val exportTitle = "Esporta Impostazioni"
    override val exportMessage = "Esportare le impostazioni in un file?"
    override val includeAffiliateKey = "Includi Chiave Affiliato"
    override val export = "Esporta"
    override val importTitle = "Importa Impostazioni"
    override val importMessage = "Incolla il tuo JSON di impostazioni esportato:"
    override val pasteJsonHere = "Incolla JSON qui"
    override val import = "Importa"
    override val settingsCopiedToClipboard = "Impostazioni copiate negli appunti"
    override val settingsImportedSuccessfully = "Impostazioni importate con successo"
    override val failedToImportSettings = "Importazione impostazioni fallita"
    override val settingsExportedAndCopied = "Impostazioni esportate e copiate negli appunti"
    override val exportFailed = "Errore esportazione: "
    override val invalidJsonFormat = "Formato JSON non valido"
    override val fileLoaded = "File caricato: "
    override val fileIsEmpty = "File vuoto"
    override val failedToReadFile = "Errore lettura: "
    override val browseForJsonFile = "📁 Cerca file JSON"
    override val orPasteJsonBelow = "O incolla JSON qui sotto:"
    override val validJson = "JSON valido"
    override val importing = "Importazione..."

    override val tapToPayExperimental = "Sperimentale – potrebbe non essere disponibile su tutti i dispositivi"
    override val useTapToPay = "Usa Tap to Pay"

    override val chooseColor = "Scegli Colore"
    override val suggestedColors = "Suggeriti"
    override val recentColors = "Recenti"

    override val logInSuccessful = "Accesso Riuscito"
    override val logInFailed = "Accesso Fallito"
    override val deviceConnectionSuccessful = "Dispositivo Connesso"
    override val connectionFailed = "Connessione Fallita"
    override val paymentSuccessful = "Pagamento Riuscito"
    override val paymentFailed = "Pagamento Fallito"
    override val bluetoothDisabled = "Bluetooth disattivato. Attiva il Bluetooth."
    override val cardReaderTimeout = "Timeout lettore. Verifica che sia acceso e vicino."
    override val cardReaderNotFound = "Lettore non trovato. Verifica che sia abbinato."
    override val noConnection = "Nessuna connessione al lettore"
    override val transactionDeclined = "Transazione rifiutata"
    override val noInternetConnection = "Nessuna connessione internet"
    override val notLoggedIn = "Non connesso a SumUp"
    override val locationRequired = "Posizione richiesta"
    override val affiliateKeySaved = "Chiave Affiliato Salvata"
    override val reinitializing = "Reinizializzazione..."
    override val reinitialized = "SumUp reinizializzato"
    override val tapAgainToReset = "Tocca di nuovo per reimpostare"
    override val authenticationTimedOut = "Autenticazione scaduta"
    override val waitingForInternet = "In attesa di connessione internet..."

    override val screensaver = "Salvaschermo"
    override val previewNow = "Anteprima"
    override val screensaverCustomMessage = "Messaggio personalizzato"
    override val screensaverCycleMessages = "Alterna i messaggi"
    override val screensaverDefaultMessages = listOf(
        "Sadaqah Jariyah",
        "Sostienici ora",
        "La Sadaqah accresce la tua ricchezza",
        "Ogni centesimo è un seme di ricompensa infinita",
        "Sii generoso",
        "Dai per amore di Allah",
        "جزاك الله خيرا"
    )
    override val timers = "Timer"
    override val screensaverIdleTimeout = "Inattività prima del salvaschermo"
    override val screensaverDuration = "Durata salvaschermo"
    override val screensaverCustomMessageHold = "Messaggio personalizzato"
    override val screensaverMessageHold = "Durata messaggio"
    override val thankYouDuration = "Schermata di ringraziamento"
    override val minutes = "min"
    override val seconds = "sec"

    override val setupStatus = "Stato configurazione"
    override val pinApp = "Fissa App"
    override val unpinApp = "Sblocca App"
    override val reconnectWifi = "Riconnetti WiFi"
    override val configureWifi = "Configura WiFi"
    override val enableBluetooth = "Attiva Bluetooth"
    override val disableBluetooth = "Disattiva Bluetooth"
    override val testMode = "Modalità test"
    override val testModeActive = "MODALITÀ TEST"
    override val logOut = "Disconnetti"
    override val logOutConfirmTitle = "Disconnettersi?"
    override val logOutConfirmMessage = "Verrai disconnesso da SumUp. Dovrai accedere di nuovo per elaborare i pagamenti."

    override val currency = "Valuta"
    override val euro = "Euro (€)"
    override val usDollar = "Dollaro USA ($)"
    override val britishPound = "Sterlina britannica (£)"
}

// ── Turkish ───────────────────────────────────────────────────────────────────
object TurkishStrings : Strings {
    override val back = "Geri"
    override val save = "Kaydet"
    override val cancel = "İptal"
    override val apply = "Uygula"
    override val yes = "Evet"
    override val no = "Hayır"

    override val welcome = "HOŞ GELDİNİZ"
    override val sumupAffiliateKey = "SUMUP ORTAKLIK ANAHTARI"
    override val logIn = "GİRİŞ YAP"

    override val chooseAmount = "MİKTAR SEÇİN"
    override val customAmount = "ÖZEL MİKTAR"
    override val customAmountLine1 = "ÖZEL"
    override val customAmountLine2 = "MİKTAR"

    override val enterAmount = "Miktar girin"
    override val minMaxAmount = "(€1 - €5000)"
    override val pay = "Öde"
    override val minimumDonation = "Minimum bağış €1"
    override val maximumDonation = "Maksimum bağış €5000"
    override val enterValidAmount = "Geçerli miktar girin"

    override val thankYou = "بارك الله\nفيك"
    override val thankYouLocalized = "Teşekkür ederiz"

    override val maintenance = "BAKIM"
    override val pleaseWait = "Lütfen bekleyin..."

    override val settings = "AYARLAR"
    override val kioskName = "Kiosk Adı"
    override val kioskNamePlaceholder = "Örn: İstanbul Camii"
    override val logoImage = "Logo Resmi"
    override val selectLogo = "Logo Seç"
    override val colors = "Renkler"
    override val background = "Arka Plan"
    override val pattern = "Desen"
    override val buttons = "Düğmeler"
    override val textBorder = "Metin/Kenar"
    override val exportSettings = "Dışa Aktar"
    override val importSettings = "İçe Aktar"
    override val saveAndBack = "Kaydet & Geri"
    override val resetApp = "Uygulamayı Sıfırla"
    override val noLogoSelected = "Logo seçilmedi"
    override val language = "Dil"
    override val cardReader = "Kart Okuyucu"
    override val connectCardReaderButton = "Okuyucu Bağla"
    override val loginFirstToConnect = "Okuyucuyu bağlamak için önce giriş yapın"
    override val experimental = "Deneysel"
    override val thankYouToggleLabel = "Teşekkür mesajı"
    override val thankYouToggleDesc = "Çevrilmiş teşekkür yerine Arapça İslami dua göster"

    override val exportTitle = "Ayarları Dışa Aktar"
    override val exportMessage = "Ayarlar bir dosyaya aktarılsın mı?"
    override val includeAffiliateKey = "Ortaklık Anahtarını Dahil Et"
    override val export = "Dışa Aktar"
    override val importTitle = "Ayarları İçe Aktar"
    override val importMessage = "Dışa aktarılan JSON ayarlarını yapıştırın:"
    override val pasteJsonHere = "JSON buraya yapıştırın"
    override val import = "İçe Aktar"
    override val settingsCopiedToClipboard = "Ayarlar panoya kopyalandı"
    override val settingsImportedSuccessfully = "Ayarlar başarıyla içe aktarıldı"
    override val failedToImportSettings = "Ayarlar içe aktarılamadı"
    override val settingsExportedAndCopied = "Ayarlar dışa aktarıldı ve panoya kopyalandı"
    override val exportFailed = "Dışa aktarma hatası: "
    override val invalidJsonFormat = "Geçersiz JSON formatı"
    override val fileLoaded = "Dosya yüklendi: "
    override val fileIsEmpty = "Dosya boş"
    override val failedToReadFile = "Dosya okuma hatası: "
    override val browseForJsonFile = "📁 JSON dosyasına göz at"
    override val orPasteJsonBelow = "Veya aşağıya JSON yapıştırın:"
    override val validJson = "Geçerli JSON"
    override val importing = "İçe aktarılıyor..."

    override val tapToPayExperimental = "Deneysel – tüm cihazlarda mevcut olmayabilir"
    override val useTapToPay = "Tap to Pay Kullan"

    override val chooseColor = "Renk Seç"
    override val suggestedColors = "Önerilen"
    override val recentColors = "Son kullanılan"

    override val logInSuccessful = "Giriş Başarılı"
    override val logInFailed = "Giriş Başarısız"
    override val deviceConnectionSuccessful = "Cihaz Bağlandı"
    override val connectionFailed = "Bağlantı Başarısız"
    override val paymentSuccessful = "Ödeme Başarılı"
    override val paymentFailed = "Ödeme Başarısız"
    override val bluetoothDisabled = "Bluetooth devre dışı. Bluetooth'u etkinleştirin."
    override val cardReaderTimeout = "Kart okuyucu zaman aşımı. Okuyucunun açık ve yakında olduğunu kontrol edin."
    override val cardReaderNotFound = "Kart okuyucu bulunamadı. Eşleştirildiğini kontrol edin."
    override val noConnection = "Okuyucuya bağlantı yok"
    override val transactionDeclined = "İşlem reddedildi"
    override val noInternetConnection = "İnternet bağlantısı yok"
    override val notLoggedIn = "SumUp'a giriş yapılmadı"
    override val locationRequired = "Konum gerekli"
    override val affiliateKeySaved = "Ortaklık Anahtarı Kaydedildi"
    override val reinitializing = "Yeniden başlatılıyor..."
    override val reinitialized = "SumUp yeniden başlatıldı"
    override val tapAgainToReset = "Sıfırlamak için tekrar dokunun"
    override val authenticationTimedOut = "Kimlik doğrulama zaman aşımına uğradı"
    override val waitingForInternet = "İnternet bağlantısı bekleniyor..."

    override val screensaver = "Ekran Koruyucu"
    override val previewNow = "Şimdi Önizle"
    override val screensaverCustomMessage = "Özel Mesaj"
    override val screensaverCycleMessages = "Mesajları döndür"
    override val screensaverDefaultMessages = listOf(
        "Sadaqah Jariyah",
        "Bize şimdi destek olun",
        "Sadaka zenginliğinizi artırır",
        "Her kuruş sonsuz ecrin tohumudur",
        "Cömertler arasında olun",
        "Allah rızası için verin",
        "جزاك الله خيرا"
    )
    override val timers = "Zamanlayıcılar"
    override val screensaverIdleTimeout = "Ekran koruyucu öncesi bekleme"
    override val screensaverDuration = "Ekran koruyucu süresi"
    override val screensaverCustomMessageHold = "Özel mesaj süresi"
    override val screensaverMessageHold = "Mesaj süresi"
    override val thankYouDuration = "Teşekkür ekranı"
    override val minutes = "dk"
    override val seconds = "sn"

    override val setupStatus = "Kurulum Durumu"
    override val pinApp = "Uygulamayı Sabitle"
    override val unpinApp = "Uygulamayı Çöz"
    override val reconnectWifi = "WiFi'ya Yeniden Bağlan"
    override val configureWifi = "WiFi'ı Yapılandır"
    override val enableBluetooth = "Bluetooth'u Etkinleştir"
    override val disableBluetooth = "Bluetooth'u Devre Dışı Bırak"
    override val testMode = "Test Modu"
    override val testModeActive = "TEST MODU"
    override val logOut = "Çıkış Yap"
    override val logOutConfirmTitle = "Çıkış yapılsın mı?"
    override val logOutConfirmMessage = "SumUp hesabınızdan çıkış yapılacak. Ödemeleri işlemek için tekrar giriş yapmanız gerekecek."

    override val currency = "Para Birimi"
    override val euro = "Euro (€)"
    override val usDollar = "ABD Doları ($)"
    override val britishPound = "İngiliz Sterlini (£)"
}

// ── Arabic ────────────────────────────────────────────────────────────────────
object ArabicStrings : Strings {
    override val back = "رجوع"
    override val save = "حفظ"
    override val cancel = "إلغاء"
    override val apply = "تطبيق"
    override val yes = "نعم"
    override val no = "لا"

    override val welcome = "مرحباً"
    override val sumupAffiliateKey = "مفتاح شريك SUMUP"
    override val logIn = "تسجيل الدخول"

    override val chooseAmount = "اختر المبلغ"
    override val customAmount = "مبلغ مخصص"
    override val customAmountLine1 = "مبلغ"
    override val customAmountLine2 = "مخصص"

    override val enterAmount = "أدخل المبلغ"
    override val minMaxAmount = "(€1 - €5000)"
    override val pay = "ادفع"
    override val minimumDonation = "الحد الأدنى للتبرع €1"
    override val maximumDonation = "الحد الأقصى للتبرع €5000"
    override val enterValidAmount = "أدخل مبلغاً صحيحاً"

    override val thankYou = "بارك الله\nفيك"
    override val thankYouLocalized = "شكراً جزيلاً"

    override val maintenance = "صيانة"
    override val pleaseWait = "يرجى الانتظار..."

    override val settings = "الإعدادات"
    override val kioskName = "اسم الكشك"
    override val kioskNamePlaceholder = "مثال: مسجد لندن"
    override val logoImage = "صورة الشعار"
    override val selectLogo = "اختر الشعار"
    override val colors = "الألوان"
    override val background = "الخلفية"
    override val pattern = "النمط"
    override val buttons = "الأزرار"
    override val textBorder = "النص/الحدود"
    override val exportSettings = "تصدير"
    override val importSettings = "استيراد"
    override val saveAndBack = "حفظ والعودة"
    override val resetApp = "إعادة تعيين التطبيق"
    override val noLogoSelected = "لم يتم اختيار شعار"
    override val language = "اللغة"
    override val cardReader = "قارئ البطاقات"
    override val connectCardReaderButton = "توصيل القارئ"
    override val loginFirstToConnect = "سجل الدخول أولاً لتوصيل القارئ"
    override val experimental = "تجريبي"
    override val thankYouToggleLabel = "رسالة الشكر"
    override val thankYouToggleDesc = "عرض الدعاء الإسلامي بالعربية بدلاً من الشكر المترجم"

    override val exportTitle = "تصدير الإعدادات"
    override val exportMessage = "هل تريد تصدير الإعدادات إلى ملف؟"
    override val includeAffiliateKey = "تضمين مفتاح الشريك"
    override val export = "تصدير"
    override val importTitle = "استيراد الإعدادات"
    override val importMessage = "الصق JSON الإعدادات المصدرة:"
    override val pasteJsonHere = "الصق JSON هنا"
    override val import = "استيراد"
    override val settingsCopiedToClipboard = "تم نسخ الإعدادات إلى الحافظة"
    override val settingsImportedSuccessfully = "تم استيراد الإعدادات بنجاح"
    override val failedToImportSettings = "فشل استيراد الإعدادات"
    override val settingsExportedAndCopied = "تم تصدير الإعدادات ونسخها إلى الحافظة"
    override val exportFailed = "فشل التصدير: "
    override val invalidJsonFormat = "تنسيق JSON غير صالح"
    override val fileLoaded = "تم تحميل الملف: "
    override val fileIsEmpty = "الملف فارغ"
    override val failedToReadFile = "خطأ في قراءة الملف: "
    override val browseForJsonFile = "📁 تصفح ملف JSON"
    override val orPasteJsonBelow = "أو الصق JSON أدناه:"
    override val validJson = "JSON صالح"
    override val importing = "جارٍ الاستيراد..."

    override val tapToPayExperimental = "تجريبي – قد لا يكون متاحاً على جميع الأجهزة"
    override val useTapToPay = "استخدم Tap to Pay"

    override val chooseColor = "اختر اللون"
    override val suggestedColors = "مقترحة"
    override val recentColors = "الأخيرة"

    override val logInSuccessful = "تم تسجيل الدخول بنجاح"
    override val logInFailed = "فشل تسجيل الدخول"
    override val deviceConnectionSuccessful = "تم توصيل الجهاز"
    override val connectionFailed = "فشل الاتصال"
    override val paymentSuccessful = "تمت الدفعة بنجاح"
    override val paymentFailed = "فشلت الدفعة"
    override val bluetoothDisabled = "البلوتوث معطل. يرجى تفعيل البلوتوث."
    override val cardReaderTimeout = "انتهت مهلة القارئ. تحقق من تشغيله وقربه."
    override val cardReaderNotFound = "لم يُعثر على القارئ. تحقق من إقرانه."
    override val noConnection = "لا يوجد اتصال بالقارئ"
    override val transactionDeclined = "تم رفض المعاملة"
    override val noInternetConnection = "لا يوجد اتصال بالإنترنت"
    override val notLoggedIn = "غير مسجل في SumUp"
    override val locationRequired = "الموقع مطلوب"
    override val affiliateKeySaved = "تم حفظ مفتاح الشريك"
    override val reinitializing = "جارٍ إعادة التهيئة..."
    override val reinitialized = "تم إعادة تهيئة SumUp"
    override val tapAgainToReset = "اضغط مرة أخرى لإعادة التعيين"
    override val authenticationTimedOut = "انتهت مهلة المصادقة"
    override val waitingForInternet = "في انتظار الاتصال بالإنترنت..."

    override val screensaver = "شاشة التوقف"
    override val previewNow = "معاينة الآن"
    override val screensaverCustomMessage = "رسالة مخصصة"
    override val screensaverCycleMessages = "تبديل الرسائل"
    override val screensaverDefaultMessages = listOf(
        "صدقة جارية",
        "ادعمنا الآن",
        "الصدقة تُنمّي مالك",
        "كل درهم بذرة أجر لا ينقطع",
        "كن من الكرماء",
        "أعطِ في سبيل الله",
        "جزاك الله خيرا"
    )
    override val timers = "المؤقتات"
    override val screensaverIdleTimeout = "الخمول قبل الشاشة"
    override val screensaverDuration = "مدة الشاشة"
    override val screensaverCustomMessageHold = "مدة الرسالة المخصصة"
    override val screensaverMessageHold = "مدة الرسالة"
    override val thankYouDuration = "شاشة الشكر"
    override val minutes = "د"
    override val seconds = "ث"

    override val setupStatus = "حالة الإعداد"
    override val pinApp = "تثبيت التطبيق"
    override val unpinApp = "إلغاء التثبيت"
    override val reconnectWifi = "إعادة الاتصال بالواي فاي"
    override val configureWifi = "إعداد الواي فاي"
    override val enableBluetooth = "تفعيل البلوتوث"
    override val disableBluetooth = "تعطيل البلوتوث"
    override val testMode = "وضع الاختبار"
    override val testModeActive = "وضع الاختبار"
    override val logOut = "تسجيل الخروج"
    override val logOutConfirmTitle = "تسجيل الخروج؟"
    override val logOutConfirmMessage = "سيتم تسجيل خروجك من SumUp. ستحتاج إلى تسجيل الدخول مجدداً لمعالجة المدفوعات."

    override val currency = "العملة"
    override val euro = "يورو (€)"
    override val usDollar = "دولار أمريكي ($)"
    override val britishPound = "جنيه إسترليني (£)"
}
