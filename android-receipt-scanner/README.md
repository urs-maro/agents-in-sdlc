# Bon Scanner (Android)

Fotografiază un bon sau o factură fiscală. Aplicația detectează marginile, decupează documentul și îi corectează perspectiva, la fel ca Preview pe iPhone (ML Kit Document Scanner). Apoi recunoaște textul pe telefon (ML Kit OCR) și adaugă un rând în foaia **Registru** din Google Sheets, **în ordine cronologică după dată**.

**Se trimite doar dacă pe document apare CUI-ul/CIF-ul 55557417** (acceptă și `RO55557417` sau `5555 7417`). Apps Script verifică și el codul.

## Ce completează (foaia Registru, de la rândul 11)
| Coloană | Sursa |
|---|---|
| B Data | prima dată găsită (zz.ll.aaaa, zz/ll/aaaa, aaaa-ll-zz) |
| C Document | „Factura <serie> <nr>” sau „Bon fiscal <nr>” |
| D Partener | primul rând cu SRL/SA/PFA/II (fără numele tău) |
| E Categorie | „Cheltuieli” sau „Încasare servicii IT” (se poate edita) |
| F–I Sumă | TOTAL. Plată sau Încasare × Bancă sau Numerar, ales automat (CARD/POS → Bancă); se poate schimba |
| A, J, K, L | formulele (Nr., solduri) sunt rescrise automat |

Încasare = factură emisă de tine (CUI-ul tău apare la Furnizor, înainte de Cumpărător). Orice alt document = Plată. Înainte de trimitere poți corecta toate câmpurile.

## Configurare (o singură dată)
1. Deschide sheet-ul → **Extensii → Apps Script**. Lipește conținutul din `apps-script/Code.gs` și salvează.
2. **Implementare → Implementare nouă → Aplicație web**. Execută ca: *Eu*. Acces: *Oricine*. Copiază URL-ul `…/exec`.
3. Instalează APK-ul. Îl găsești în GitHub → Actions → „Build Bon Scanner APK” → artefactul `bon-scanner-apk`. Lipește URL-ul în câmpul de jos din aplicație.

> URL-ul web app-ul funcționează ca o parolă: oricine îl are poate adăuga rânduri. Nu-l distribui.

## Build local
Ai nevoie de Android Studio sau de JDK 17 + Android SDK. Rulează `gradle assembleDebug` în acest folder.
