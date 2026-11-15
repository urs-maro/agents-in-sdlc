/**
 * Bon Scanner – primește rânduri de la aplicația Android și le adaugă în foaia "Registru".
 * După fiecare adăugare SORTEAZĂ toate înregistrările după dată (coloana B),
 * deci registrul rămâne mereu în ordine cronologică, oricând ai scana un document.
 * Formulele din A (Nr.) și J/K/L (solduri) depind doar de poziția rândului,
 * așa că se recalculează singure după sortare.
 */
const SHEET = 'Registru';
const FIRST_ROW = 11;   // primul rând cu înregistrări
const VERSION = 'v3-sortare';

function doPost(e) {
  const lock = LockService.getScriptLock();
  lock.waitLock(20000);
  try {
    const p = JSON.parse(e.postData.contents);
    if (String(p.cif || '') !== '55557417') return out({ ok: false, error: 'CUI lipsă' });
    if (!/^[FGHI]$/.test(p.column)) return out({ ok: false, error: 'coloană invalidă' });
    if (!/^\d{4}-\d{2}-\d{2}$/.test(p.date)) return out({ ok: false, error: 'dată invalidă' });

    const sh = SpreadsheetApp.getActive().getSheetByName(SHEET);

    // ultimul rând care are o dată în coloana B
    const height = Math.max(sh.getLastRow() - FIRST_ROW + 1, 1);
    const colB = sh.getRange(FIRST_ROW, 2, height, 1).getValues();
    let last = FIRST_ROW - 1;
    colB.forEach((r, i) => { if (r[0] !== '' && r[0] !== null) last = FIRST_ROW + i; });
    const row = last + 1;

    // data ca număr de zi Sheets (fără oră) => fără decalaje de fus orar
    const [y, m, d] = p.date.split('-').map(Number);
    const serial = Math.round((Date.UTC(y, m - 1, d) - Date.UTC(1899, 11, 30)) / 86400000);

    const vals = [serial, p.document || '', p.partner || '', p.category || '', '', '', '', ''];
    vals[4 + 'FGHI'.indexOf(p.column)] = Number(p.amount);
    sh.getRange(row, 2, 1, 8).setValues([vals]);
    sh.getRange(row, 2).setNumberFormat('dd.mm.yyyy');
    sh.getRange(row, 6, 1, 4).setNumberFormat('#,##0.00');

    // formulele pe rândul nou (dacă lipsesc)
    sh.getRange(row, 1).setFormula(`=IF(B${row}="","",ROW()-10)`);
    sh.getRange(row, 10, 1, 3).setFormulas([[
      `=IF(COUNT(F${row}:I${row})=0,"",$J$7+SUM($F$${FIRST_ROW}:F${row})-SUM($H$${FIRST_ROW}:H${row}))`,
      `=IF(COUNT(F${row}:I${row})=0,"",$K$7+SUM($G$${FIRST_ROW}:G${row})-SUM($I$${FIRST_ROW}:I${row}))`,
      `=IF(J${row}="","",J${row}+K${row})`,
    ]]);

    // sortare cronologică: doar datele (B–I), formulele rămân pe loc
    sh.getRange(FIRST_ROW, 2, row - FIRST_ROW + 1, 8).sort({ column: 2, ascending: true });

    // pe ce rând a ajuns înregistrarea nouă după sortare
    const sorted = sh.getRange(FIRST_ROW, 2, row - FIRST_ROW + 1, 3).getValues();
    let finalRow = row;
    for (let i = sorted.length - 1; i >= 0; i--) {
      const v = sorted[i][0];
      const s = v instanceof Date
        ? Math.round((Date.UTC(v.getFullYear(), v.getMonth(), v.getDate()) - Date.UTC(1899, 11, 30)) / 86400000)
        : v;
      if (s === serial && sorted[i][1] === vals[1] && sorted[i][2] === vals[2]) { finalRow = FIRST_ROW + i; break; }
    }
    return out({ ok: true, row: finalRow, version: VERSION });
  } catch (err) {
    return out({ ok: false, error: String(err) });
  } finally {
    lock.releaseLock();
  }
}

/** Deschide linkul /exec în browser: dacă vezi "v3-sortare", rulează scriptul corect. */
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
    .addToUi();
}

/** Rulează o dată: creează declanșatorul lunar (ziua 1, ora 7). */
function instaleazaPdfLunar() {
  ScriptApp.getProjectTriggers()
    .filter(t => t.getHandlerFunction() === 'genereazaPdfLunaTrecuta')
    .forEach(t => ScriptApp.deleteTrigger(t));
  ScriptApp.newTrigger('genereazaPdfLunaTrecuta').timeBased().onMonthDay(1).atHour(7).create();
  try {
    SpreadsheetApp.getUi().alert('Gata: PDF-ul registrului se va genera automat pe 1 ale fiecărei luni.');
  } catch (e) { /* rulat din editor */ }
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
      '.\nO copie este salvată în Google Drive: ' + file.getUrl() + '\n\n— Contabil Primar',
    attachments: [blob],
  });

  // revine la luna afișată înainte
  sh.getRange('B6').setValue(vechi[0]);
  sh.getRange('D6').setValue(vechi[1]);
  return file;
}

function subfolder(parent, name) {
  const it = parent.getFoldersByName(name);
  return it.hasNext() ? it.next() : parent.createFolder(name);
}
