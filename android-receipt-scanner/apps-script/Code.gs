/**
 * Bon Scanner – primește rânduri de la aplicația Android și le adaugă în foaia "Registru".
 * După fiecare adăugare SORTEAZĂ toate înregistrările după dată (coloana B),
 * deci registrul rămâne mereu în ordine cronologică, oricând ai scana un document.
 * Formulele din A (Nr.) și J/K/L (solduri) depind doar de poziția rândului,
 * așa că se recalculează singure după sortare.
 */
const SHEET = 'Registru';
const FIRST_ROW = 11;   // primul rând cu înregistrări
const VERSION = 'v5-aport';

function doPost(e) {
  const lock = LockService.getScriptLock();
  lock.waitLock(20000);
  try {
    const p = JSON.parse(e.postData.contents);
    if (String(p.cif || '') !== '55557417') return out({ ok: false, error: 'CUI lipsă' });
    if (!/^[FGHI]$/.test(p.column)) return out({ ok: false, error: 'coloană invalidă' });
    if (!/^\d{4}-\d{2}-\d{2}$/.test(p.date)) return out({ ok: false, error: 'dată invalidă' });
    if (p.aport === true && p.column === 'I') {
      // plată din banii personali ai titularului: aport (încasare numerar) + plata (numerar), aceeași zi
      const aport = {
        date: p.date, document: 'Aport titular',
        partner: 'Titular – plată ' + (p.partner || p.document || '') + ' din bani personali',
        category: 'Aport / retragere titular', amount: p.amount, column: 'G',
      };
      const rows = adaugaRanduri([aport, p]);
      return out({ ok: true, row: rows[1], aportRow: rows[0], version: VERSION });
    }
    const rows = adaugaRanduri([p]);
    return out({ ok: true, row: rows[0], version: VERSION });
  } catch (err) {
    return out({ ok: false, error: String(err) });
  } finally {
    lock.releaseLock();
  }
}

/** Serial de zi Google Sheets pentru o dată aaaa-ll-zz (fără oră => fără fus orar). */
function serialZi(iso) {
  const [y, m, d] = iso.split('-').map(Number);
  return Math.round((Date.UTC(y, m - 1, d) - Date.UTC(1899, 11, 30)) / 86400000);
}

/**
 * Adaugă înregistrări în Registru, apoi sortează tot registrul după dată.
 * Fiecare element: {date:'aaaa-ll-zz', document, partner, category, amount, column:'F'|'G'|'H'|'I'}.
 * Întoarce rândurile pe care au ajuns după sortare.
 */
function adaugaRanduri(items) {
  const sh = SpreadsheetApp.getActive().getSheetByName(SHEET);
  const height = Math.max(sh.getLastRow() - FIRST_ROW + 1, 1);
  const colB = sh.getRange(FIRST_ROW, 2, height, 1).getValues();
  let last = FIRST_ROW - 1;
  colB.forEach((r, i) => { if (r[0] !== '' && r[0] !== null) last = FIRST_ROW + i; });

  const added = [];
  items.forEach((p, k) => {
    const row = last + 1 + k;
    const vals = [serialZi(p.date), p.document || '', p.partner || '', p.category || '', '', '', '', ''];
    vals[4 + 'FGHI'.indexOf(p.column)] = Math.round(Number(p.amount) * 100) / 100;
    sh.getRange(row, 2, 1, 8).setValues([vals]);
    sh.getRange(row, 2).setNumberFormat('dd.mm.yyyy');
    sh.getRange(row, 6, 1, 4).setNumberFormat('#,##0.00');
    sh.getRange(row, 1).setFormula(`=IF(B${row}="","",ROW()-10)`);
    sh.getRange(row, 10, 1, 3).setFormulas([[
      `=IF(COUNT(F${row}:I${row})=0,"",$J$7+SUM($F$${FIRST_ROW}:F${row})-SUM($H$${FIRST_ROW}:H${row}))`,
      `=IF(COUNT(F${row}:I${row})=0,"",$K$7+SUM($G$${FIRST_ROW}:G${row})-SUM($I$${FIRST_ROW}:I${row}))`,
      `=IF(J${row}="","",J${row}+K${row})`,
    ]]);
    added.push(vals);
  });
  const end = last + items.length;
  if (end < FIRST_ROW) return [];

  // sortare cronologică: doar datele (B–I); formulele rămân pe loc
  sh.getRange(FIRST_ROW, 2, end - FIRST_ROW + 1, 8).sort([{ column: 2, ascending: true }, { column: 7, ascending: false }]);

  const sorted = sh.getRange(FIRST_ROW, 2, end - FIRST_ROW + 1, 3).getValues().map(r => [
    r[0] instanceof Date ? Math.round((Date.UTC(r[0].getFullYear(), r[0].getMonth(), r[0].getDate()) - Date.UTC(1899, 11, 30)) / 86400000) : r[0],
    r[1], r[2]]);
  return added.map(v => {
    const i = sorted.findIndex(r => r[0] === v[0] && r[1] === v[1] && r[2] === v[2]);
    return i >= 0 ? FIRST_ROW + i : null;
  });
}

/** Deschide linkul /exec în browser: dacă vezi "v5-aport", rulează scriptul corect. */
function doGet() {
  return out({ ok: true, version: VERSION });
}

function out(o) {
  return ContentService.createTextOutput(JSON.stringify(o)).setMimeType(ContentService.MimeType.JSON);
}

/* =====================================================================
 * PDF LUNAR – Registrul de încasări și plăți
 * Pe 1 ale lunii generează PDF-ul lunii trecute din tab-ul "Tipărire registru",
 * îl salvează în Drive (Contabil Primar / Registru <an>) și îl trimite pe email.
 * Instalare: rulează o singură dată funcția instaleazaPdfLunar().
 * ===================================================================== */
const PRINT_SHEET = 'Tipărire registru';
const LUNI = ['ianuarie', 'februarie', 'martie', 'aprilie', 'mai', 'iunie', 'iulie',
  'august', 'septembrie', 'octombrie', 'noiembrie', 'decembrie'];

/** Meniul "Contabil Primar" din sheet. */
function onOpen() {
  SpreadsheetApp.getUi().createMenu('Contabil Primar')
    .addItem('Generează PDF pentru o lună…', 'genereazaPdfManual')
    .addItem('Generează PDF pentru luna trecută', 'genereazaPdfLunaTrecuta')
    .addSeparator()
    .addItem('Activează PDF automat lunar', 'instaleazaPdfLunar')
    .addSeparator()
    .addItem('Sincronizează Oblio acum', 'sincronizeazaOblio')
    .addItem('Activează sincronizarea zilnică Oblio', 'instaleazaOblio')
    .addToUi();
}

/** Rulează o dată: creează declanșatorul lunar (ziua 1, ora 7). */
function instaleazaPdfLunar() {
  ScriptApp.getProjectTriggers()
    .filter(t => t.getHandlerFunction() === 'genereazaPdfLunaTrecuta')
    .forEach(t => ScriptApp.deleteTrigger(t));
  ScriptApp.newTrigger('genereazaPdfLunaTrecuta').timeBased().onMonthDay(1).atHour(7).create();
  console.log('Gata: PDF-ul registrului se va genera automat pe 1 ale fiecărei luni.');
  try {
    SpreadsheetApp.getActive().toast('PDF-ul registrului se va genera automat pe 1 ale fiecărei luni.', 'Contabil Primar', 8);
  } catch (e) { /* fără foaie deschisă */ }
}

function genereazaPdfLunaTrecuta() {
  const tz = SpreadsheetApp.getActive().getSpreadsheetTimeZone();
  const azi = new Date();
  const an = Number(Utilities.formatDate(azi, tz, 'yyyy'));
  const luna = Number(Utilities.formatDate(azi, tz, 'M'));
  const l = luna === 1 ? 12 : luna - 1;
  genereazaPdf(l, luna === 1 ? an - 1 : an);
}

function genereazaPdfManual() {
  const ui = SpreadsheetApp.getUi();
  const r = ui.prompt('PDF registru', 'Luna și anul (ex: 11.2026):', ui.ButtonSet.OK_CANCEL);
  if (r.getSelectedButton() !== ui.Button.OK) return;
  const m = r.getResponseText().trim().match(/^(\d{1,2})[./\-\s](\d{4})$/);
  if (!m || +m[1] < 1 || +m[1] > 12) { ui.alert('Format greșit. Scrie de exemplu 11.2026'); return; }
  const f = genereazaPdf(+m[1], +m[2]);
  ui.alert('PDF salvat în Drive și trimis pe email:\n' + f.getName());
}

/** Generează PDF-ul pentru luna/anul date; îl salvează și îl trimite. */
function genereazaPdf(luna, an) {
  const ss = SpreadsheetApp.getActive();
  const sh = ss.getSheetByName(PRINT_SHEET);
  const vechi = [sh.getRange('B6').getValue(), sh.getRange('D6').getValue()];
  sh.getRange('B6').setValue(luna);
  sh.getRange('D6').setValue(an);
  SpreadsheetApp.flush();
  Utilities.sleep(3000); // lasă formulele să se recalculeze

  const url = 'https://docs.google.com/spreadsheets/d/' + ss.getId() + '/export?' + [
    'format=pdf', 'gid=' + sh.getSheetId(), 'size=A4', 'portrait=true', 'fitw=true',
    'gridlines=false', 'printtitle=false', 'sheetnames=false', 'fzr=true',
    'pagenum=CENTER', 'top_margin=0.5', 'bottom_margin=0.5', 'left_margin=0.5', 'right_margin=0.5',
  ].join('&');
  const resp = UrlFetchApp.fetch(url, { headers: { Authorization: 'Bearer ' + ScriptApp.getOAuthToken() } });
  const nume = 'Registru ' + an + '-' + String(luna).padStart(2, '0') + ' ' + LUNI[luna - 1] + '.pdf';
  const blob = resp.getBlob().setName(nume);

  // Drive: Contabil Primar / Registru <an>  (înlocuiește fișierul dacă există deja)
  const folder = subfolder(subfolder(DriveApp.getRootFolder(), 'Contabil Primar'), 'Registru ' + an);
  const exist = folder.getFilesByName(nume);
  while (exist.hasNext()) exist.next().setTrashed(true);
  const file = folder.createFile(blob);

  // Email către proprietarul scriptului
  const email = Session.getEffectiveUser().getEmail();
  MailApp.sendEmail({
    to: email,
    subject: 'Registru de încasări și plăți – ' + LUNI[luna - 1] + ' ' + an,
    body: 'Atașat este Registrul de încasări și plăți pentru ' + LUNI[luna - 1] + ' ' + an +
      '.\nO copie este salvată în Google Drive: ' + file.getUrl() + '\n\n' + rezumatTaxe_(luna, an) +
      '\n\n— Contabil Primar',
    attachments: [blob],
  });

  // revine la luna afișată înainte
  sh.getRange('B6').setValue(vechi[0]);
  sh.getRange('D6').setValue(vechi[1]);
  return file;
}

/** Text pentru email din tab-ul "Taxe": suma de pus deoparte și obligațiile lunii următoare. */
function rezumatTaxe_(luna, an) {
  const sh = SpreadsheetApp.getActive().getSheetByName('Taxe');
  if (!sh) return '';
  const vechi = [sh.getRange('B4').getFormula() || sh.getRange('B4').getValue(),
                 sh.getRange('B5').getFormula() || sh.getRange('B5').getValue()];
  sh.getRange('B4').setValue(an);
  sh.getRange('B5').setValue(luna);
  SpreadsheetApp.flush();
  const suma = sh.getRange('B30').getDisplayValue();
  const estimat = sh.getRange('B26').getDisplayValue();
  const titlu = sh.getRange('A33').getDisplayValue();
  const oblig = sh.getRange('A35:B49').getDisplayValues().filter(r => r[1])
    .map(r => '  • ' + (r[0] ? r[0] + ' – ' : '') + r[1]).join('\n');
  // revine la formulele implicite
  [['B4', vechi[0]], ['B5', vechi[1]]].forEach(([c, v]) =>
    String(v).charAt(0) === '=' ? sh.getRange(c).setFormula(v) : sh.getRange(c).setValue(v));
  return 'TAXE (estimare – verifică cu contabilul):\n' +
    '  De pus deoparte acum: ' + suma + '\n' +
    '  Total taxe estimate pe an: ' + estimat + '\n\n' +
    titlu + ':\n' + (oblig || '  • nimic special');
}

function subfolder(parent, name) {
  const it = parent.getFoldersByName(name);
  return it.hasNext() ? it.next() : parent.createFolder(name);
}

/* =====================================================================
 * OBLIO – încasările facturilor emise intră automat în Registru,
 *         iar facturile neîncasate apar în tab-ul "Facturi neîncasate".
 * Configurare (Project Settings → Script Properties):
 *   OBLIO_EMAIL  = emailul contului Oblio
 *   OBLIO_SECRET = API secret (Oblio → Setări → Date cont)
 *   OBLIO_START  = (opțional) de la ce dată se importă, ex. 2026-01-01
 * ===================================================================== */
const OBLIO_API = 'https://www.oblio.eu/api';
const OBLIO_CIF = '55557417';
const UNPAID_SHEET = 'Facturi neîncasate';
const NUMERAR = ['Chitanta', 'Bon fiscal', 'Alta incasare numerar'];

function instaleazaOblio() {
  ScriptApp.getProjectTriggers()
    .filter(t => t.getHandlerFunction() === 'sincronizeazaOblio')
    .forEach(t => ScriptApp.deleteTrigger(t));
  ScriptApp.newTrigger('sincronizeazaOblio').timeBased().everyDays(1).atHour(6).create();
  const r = sincronizeazaOblio();
  console.log('Oblio: ' + r.importate + ' încasări importate, ' + r.neincasate + ' facturi neîncasate.');
}

function oblioToken_() {
  const cache = CacheService.getScriptCache();
  const t = cache.get('oblio_token');
  if (t) return t;
  const pr = PropertiesService.getScriptProperties();
  const email = pr.getProperty('OBLIO_EMAIL'), secret = pr.getProperty('OBLIO_SECRET');
  if (!email || !secret) throw new Error('Lipsesc OBLIO_EMAIL / OBLIO_SECRET în Script Properties.');
  const r = UrlFetchApp.fetch(OBLIO_API + '/authorize/token', {
    method: 'post', contentType: 'application/json', muteHttpExceptions: true,
    payload: JSON.stringify({ client_id: email, client_secret: secret }),
  });
  if (r.getResponseCode() !== 200) throw new Error('Autentificare Oblio eșuată: ' + r.getContentText());
  const j = JSON.parse(r.getContentText());
  cache.put('oblio_token', j.access_token, Math.max(60, Number(j.expires_in || 3600) - 300));
  return j.access_token;
}

function oblioGet_(path, params) {
  const q = Object.keys(params).filter(k => params[k] !== undefined && params[k] !== '')
    .map(k => encodeURIComponent(k) + '=' + encodeURIComponent(params[k])).join('&');
  for (let attempt = 0; attempt < 3; attempt++) {
    const r = UrlFetchApp.fetch(OBLIO_API + path + '?' + q, {
      headers: { Authorization: 'Bearer ' + oblioToken_() }, muteHttpExceptions: true,
    });
    const code = r.getResponseCode();
    if (code === 200) return JSON.parse(r.getContentText()).data;
    if (code === 401) { CacheService.getScriptCache().remove('oblio_token'); continue; }
    if (code === 429) { Utilities.sleep(5000); continue; }
    throw new Error('Oblio ' + path + ' ' + code + ': ' + r.getContentText());
  }
  throw new Error('Oblio ' + path + ': prea multe încercări');
}

function nr_(v) { return Number(String(v == null ? 0 : v).replace(',', '.')) || 0; }

/** Toate facturile emise de la OBLIO_START (implicit 1 ianuarie anul curent). */
function oblioFacturi_() {
  const tz = SpreadsheetApp.getActive().getSpreadsheetTimeZone();
  const start = PropertiesService.getScriptProperties().getProperty('OBLIO_START') ||
    Utilities.formatDate(new Date(), tz, 'yyyy') + '-01-01';
  const all = [];
  for (let offset = 0; ; offset += 100) {
    const page = oblioGet_('/docs/invoice/list', {
      cif: OBLIO_CIF, issuedAfter: start, limitPerPage: 100, offset: offset,
      orderBy: 'issueDate', orderDir: 'ASC',
    }) || [];
    all.push(...page);
    if (page.length < 100) break;
  }
  return all.filter(f => !truthy_(f.draft) && !truthy_(f.canceled));
}

function truthy_(v) { return v === true || v === 1 || v === '1' || v === 'true'; }

/** Sincronizarea: încasări → Registru; neîncasate → tab separat. */
function sincronizeazaOblio() {
  const lock = LockService.getScriptLock();
  lock.waitLock(30000);
  try {
    const props = PropertiesService.getScriptProperties();
    const done = new Set(JSON.parse(props.getProperty('OBLIO_IMPORTATE') || '[]'));
    const sh = SpreadsheetApp.getActive().getSheetByName(SHEET);
    const tz = SpreadsheetApp.getActive().getSpreadsheetTimeZone();

    // ce există deja în registru (ex. factură scanată din aplicație): "SERIE NR|suma"
    const last = Math.max(sh.getLastRow(), FIRST_ROW);
    const existing = sh.getRange(FIRST_ROW, 3, last - FIRST_ROW + 1, 5).getValues()
      .map(r => String(r[0]).toUpperCase().replace(/[^A-Z0-9]/g, '') + '|' + Math.round((nr_(r[3]) + nr_(r[4])) * 100));

    const facturi = oblioFacturi_();
    const noi = [], neincasate = [];
    facturi.forEach(f => {
      const serieNr = (f.seriesName || '') + ' ' + (f.number || '');
      const cheieDoc = ('FACTURA' + serieNr).toUpperCase().replace(/[^A-Z0-9]/g, '');
      const curs = (f.currency && f.currency !== 'RON') ? nr_(f.exchangeRate) || 1 : 1;
      const total = nr_(f.total) * curs;
      // detaliile (încasările) doar pentru facturile cu încasări posibile
      Utilities.sleep(400); // sub limita de ~100 cereri/minut
      const doc = oblioGet_('/docs/invoice', { cif: OBLIO_CIF, seriesName: f.seriesName, number: f.number }) || {};
      const collects = doc.collects || [];
      let incasat = 0;
      collects.forEach(c => {
        const val = nr_(c.value) * curs;
        incasat += val;
        const data = String(c.issueDate || '').slice(0, 10);
        const key = [f.seriesName, f.number, data, val.toFixed(2)].join('|');
        if (done.has(key) || !/^\d{4}-\d{2}-\d{2}$/.test(data)) return;
        done.add(key);
        if (existing.indexOf(cheieDoc + '|' + Math.round(val * 100)) >= 0) return; // deja în registru
        noi.push({
          date: data,
          document: 'Factura ' + serieNr.trim(),
          partner: (f.client && f.client.name || '') + (curs !== 1 ? ' (' + f.currency + ')' : ''),
          category: 'Încasare servicii IT',
          amount: val,
          column: NUMERAR.indexOf(c.type) >= 0 ? 'G' : 'F',
        });
      });
      const rest = Math.round((total - incasat) * 100) / 100;
      if (!truthy_(f.collected) && rest > 0.009) {
        const scad = f.dueDate ? serialZi(String(f.dueDate).slice(0, 10)) : '';
        neincasate.push([serieNr.trim(), f.client && f.client.name || '', serialZi(String(f.issueDate).slice(0, 10)),
          scad, total, incasat, rest]);
      }
    });

    if (noi.length) adaugaRanduri(noi);
    props.setProperty('OBLIO_IMPORTATE', JSON.stringify(Array.from(done)));
    scrieNeincasate_(neincasate);
    try {
      SpreadsheetApp.getActive().toast(noi.length + ' încasări noi importate; ' + neincasate.length +
        ' facturi neîncasate.', 'Oblio', 8);
    } catch (e) { /* rulat din declanșator */ }
    return { importate: noi.length, neincasate: neincasate.length };
  } finally {
    lock.releaseLock();
  }
}

function scrieNeincasate_(rows) {
  const ss = SpreadsheetApp.getActive();
  let sh = ss.getSheetByName(UNPAID_SHEET);
  if (!sh) sh = ss.insertSheet(UNPAID_SHEET);
  sh.clear();
  const tz = ss.getSpreadsheetTimeZone();
  sh.getRange(1, 1).setValue('Facturi neîncasate (din Oblio)').setFontWeight('bold').setFontSize(14);
  sh.getRange(2, 1).setValue('Actualizat: ' + Utilities.formatDate(new Date(), tz, 'dd.MM.yyyy HH:mm'));
  const head = ['Factura', 'Client', 'Data emiterii', 'Scadența', 'Total', 'Încasat', 'Rest de încasat', 'Zile întârziere'];
  sh.getRange(4, 1, 1, head.length).setValues([head]).setFontWeight('bold').setBackground('#e8e8e8');
  if (rows.length) {
    rows.sort((a, b) => (a[3] || a[2]) - (b[3] || b[2]));
    sh.getRange(5, 1, rows.length, 7).setValues(rows);
    const n = rows.length;
    sh.getRange(5, 8, n, 1).setFormulas(rows.map((r, i) =>
      [`=IF(D${5 + i}="","",MAX(0,TODAY()-D${5 + i}))`]));
    sh.getRange(5, 3, n, 2).setNumberFormat('dd.mm.yyyy');
    sh.getRange(5, 5, n, 3).setNumberFormat('#,##0.00');
    sh.getRange(5 + n, 1).setValue('TOTAL').setFontWeight('bold');
    sh.getRange(5 + n, 7).setFormula(`=SUM(G5:G${4 + n})`).setNumberFormat('#,##0.00').setFontWeight('bold');
  } else {
    sh.getRange(5, 1).setValue('Toate facturile sunt încasate. 🎉');
  }
  sh.autoResizeColumns(1, 8);
}
