/**
 * Web app care primește rânduri de la aplicația Android și le inserează
 * CRONOLOGIC în foaia "Registru" (date de la rândul 11, coloanele A–L).
 * Rescrie formulele A (Nr.) și J/K/L (solduri cumulate) ca să rămână corecte.
 */
const SHEET = 'Registru';
const FIRST_ROW = 11;

function doPost(e) {
  const lock = LockService.getScriptLock();
  lock.waitLock(20000);
  try {
    const p = JSON.parse(e.postData.contents);
    if (!/^[FGHI]$/.test(p.column)) return out({ ok: false, error: 'column' });
    if (String(p.cif || '') !== '55557417') return out({ ok: false, error: 'CUI lipsă' });
    const sh = SpreadsheetApp.getActive().getSheetByName(SHEET);
    if (!/^\d{4}-\d{2}-\d{2}$/.test(p.date)) return out({ ok: false, error: 'date' });
    const [y, m, d] = p.date.split('-').map(Number);
    const tz = SpreadsheetApp.getActive().getSpreadsheetTimeZone();
    // comparăm datele ca text aaaa-ll-zz în fusul orar al foii (fără decalaje de fus orar)
    const key = v => (v instanceof Date) ? Utilities.formatDate(v, tz, 'yyyy-MM-dd') : null;

    const height = Math.max(sh.getLastRow() - FIRST_ROW + 1, 1);
    const colB = sh.getRange(FIRST_ROW, 2, height, 1).getValues();
    let last = FIRST_ROW - 1;
    colB.forEach((r, i) => { if (r[0] !== '' && r[0] !== null) last = FIRST_ROW + i; });

    // poziția cronologică: înaintea primului rând cu dată mai mare
    let row = last + 1;
    for (let i = 0; i <= last - FIRST_ROW; i++) {
      const k = key(colB[i][0]);
      if (k && k > p.date) { row = FIRST_ROW + i; break; }
    }
    if (row === FIRST_ROW && last >= FIRST_ROW) {
      // Nu inserăm deasupra primului rând: formulele din alte celule (SUBTOTAL, Sumar lunar,
      // Dashboard) încep la rândul 11 și s-ar muta la 12. Inserăm sub el și coborâm datele.
      sh.insertRowAfter(FIRST_ROW);
      sh.getRange(FIRST_ROW, 2, 1, 8).copyTo(sh.getRange(FIRST_ROW + 1, 2, 1, 8));
      sh.getRange(FIRST_ROW, 2, 1, 8).clearContent();
    } else if (row <= last) {
      sh.insertRowBefore(row);
    }

    // data ca număr de zi Sheets (fără oră), independent de fusul orar
    const serial = Math.round((Date.UTC(y, m - 1, d) - Date.UTC(1899, 11, 30)) / 86400000);
    const vals = [serial, p.document || '', p.partner || '', p.category || '', '', '', '', ''];
    vals[4 + 'FGHI'.indexOf(p.column)] = Number(p.amount);
    sh.getRange(row, 2, 1, 8).setValues([vals]);
    sh.getRange(row, 2).setNumberFormat('dd.mm.yyyy');
    sh.getRange(row, 6, 1, 4).setNumberFormat('#,##0.00');

    const end = last + 1;
    const fA = [], fJKL = [];
    for (let r = FIRST_ROW; r <= end; r++) {
      fA.push([`=IF(B${r}="","",ROW()-10)`]);
      fJKL.push([
        `=IF(COUNT(F${r}:I${r})=0,"",$J$7+SUM($F$${FIRST_ROW}:F${r})-SUM($H$${FIRST_ROW}:H${r}))`,
        `=IF(COUNT(F${r}:I${r})=0,"",$K$7+SUM($G$${FIRST_ROW}:G${r})-SUM($I$${FIRST_ROW}:I${r}))`,
        `=IF(J${r}="","",J${r}+K${r})`,
      ]);
    }
    sh.getRange(FIRST_ROW, 1, fA.length, 1).setFormulas(fA);
    sh.getRange(FIRST_ROW, 10, fJKL.length, 3).setFormulas(fJKL);
    return out({ ok: true, row: row });
  } catch (err) {
    return out({ ok: false, error: String(err) });
  } finally {
    lock.releaseLock();
  }
}

function out(o) {
  return ContentService.createTextOutput(JSON.stringify(o)).setMimeType(ContentService.MimeType.JSON);
}
