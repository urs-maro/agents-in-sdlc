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
