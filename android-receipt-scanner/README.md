# Contabil Primar (Android + Google Sheets)

Aplicație Android pentru o întreprindere individuală (II) neplătitoare de TVA, în sistem real.
Fotografiezi un bon sau o factură, aplicația decupează documentul, citește textul și adaugă un rând
în **Registrul de încasări și plăți** din Google Sheets, sortat cronologic.
© 2026 CRIO Software.

## Arhitectura

```
Telefon (aplicația Android)                     Google Sheets (fișierul firmei)
───────────────────────────                     ─────────────────────────────────
1. ML Kit Document Scanner  → decupare            Apps Script (apps-script/Code.gs)
2. ML Kit Text Recognition  → text + poziții      ├─ doPost: primește rândul, verifică CUI,
3. ReceiptParser            → dată, document,     │   adaugă în "Registru", sortează după dată
   partener, sumă, categorie, bancă/numerar       ├─ PDF lunar → Drive + email (declanșator lunar)
4. Ecran de verificare (utilizatorul corectează)  └─ Sincronizare Oblio (declanșator zilnic)
5. POST JSON  ───────────────────────────────►   Tab-uri calculate din "Registru":
                                                   Dashboard, Sumar lunar, Taxe,
                                                   Tipărire registru, Registru inventar,
                                                   Facturi neîncasate
```

Principii urmate:

- **Telefonul doar citește și propune; utilizatorul confirmă.** Nimic nu se trimite fără ecranul de verificare.
- **O singură sursă de adevăr:** tab-ul `Registru`. Toate celelalte tab-uri sunt formule peste el.
- **Siguranță:** se trimite doar dacă pe document apare CUI-ul firmei; scriptul verifică din nou.
- **Fără secrete în cod:** cheia Oblio stă în *Script Properties*, linkul scriptului în setările aplicației.
- **Fiecare regulă fiscală e un parametru editabil** (celule galbene în `Taxe`), marcat „DE VERIFICAT”.
- **Teste pe date reale:** parserul are teste construite din bonuri reale, cu textul împărțit pe coloane cum îl dă OCR-ul.

## Structura

| Cale | Ce conține |
|---|---|
| `app/src/main/java/ro/cretu/bonscanner/MainActivity.kt` | ecranul, scanarea, OCR, trimiterea, Setări, Actualizare, Despre |
| `app/src/main/java/ro/cretu/bonscanner/ReceiptParser.kt` | reconstruirea rândurilor după poziție + extragerea câmpurilor |
| `app/src/main/java/ro/cretu/bonscanner/SheetsClient.kt` | POST/GET către Apps Script (urmează redirecționările Google) |
| `app/src/main/java/ro/cretu/bonscanner/UpdateChecker.kt` | verifică ultima versiune publicată pe GitHub |
| `app/src/test/...` | teste unitare (bonuri reale, praguri, versiuni) |
| `apps-script/Code.gs` | tot codul din Google Sheets |
| `../.github/workflows/android-receipt-scanner.yml` | compilare + teste + publicare APK |

## Lucruri care au contat (lecții)

1. **OCR-ul citește pe coloane.** „TOTAL:” și „456,91” ajung în blocuri diferite; rândurile se refac după poziția verticală (`ReceiptParser.buildRows`).
2. **Cuvinte scurte doar ca întreg:** „RCA” apare și în „MASTERCARD”.
3. **Datele se scriu în Sheets ca număr de zi**, nu ca `Date` JavaScript — altfel fusul orar le mută cu o zi.
4. **Nu se inserează rânduri deasupra primului rând de date:** formulele altor tab-uri își mută intervalul. Scriptul adaugă la final și sortează.
5. **Cheie de semnare fixă** (`app/contabil-primar.keystore`), altfel fiecare compilare cere dezinstalare.
6. **Fontul la tipărire:** Calibri nu e font Google; diacriticele românești ies dintr-un font de rezervă. Tab-urile tipărite folosesc Open Sans.
7. **Aportul titularului** (bani personali puși în firmă) se înregistrează, dar se exclude din venituri și profit.

## Compilare și instalare

- La fiecare push, GitHub Actions rulează testele, compilează și publică APK-ul:
  `https://github.com/urs-maro/agents-in-sdlc/releases/download/contabil-primar/ContabilPrimar.apk`
- Versiunea se schimbă în `app/build.gradle.kts` (`versionCode` + `versionName`); butonul „Actualizare” din aplicație o detectează.
- Local: Android Studio sau JDK 17 + Android SDK, `gradle testDebugUnitTest assembleDebug`.

## Configurare Google Sheets

1. Extensions → Apps Script: lipești `apps-script/Code.gs`, salvezi.
2. Deploy → New deployment → Web app (Execute as: Me, Access: Anyone). Linkul `/exec` îl pui în aplicație la ⚙️ Setări.
3. La modificări ulterioare: Deploy → Manage deployments → ✏️ → New version (linkul rămâne același).
4. O singură dată: rulezi `instaleazaPdfLunar` și, opțional, `instaleazaOblio` (cu `OBLIO_EMAIL`, `OBLIO_SECRET` în Script Properties).
