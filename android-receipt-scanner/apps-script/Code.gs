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
    const [y, m, d] = p.date.split('-').map(Number);
    const date = new Date(y, m - 1, d);

    const height = Math.max(sh.getLastRow() - FIRST_ROW + 1, 1);
    const colB = sh.getRange(FIRST_ROW, 2, height, 1).getValues();
    let last = FIRST_ROW - 1;
    colB.forEach((r, i) => { if (r[0] !== '' && r[0] !== null) last = FIRST_ROW + i; });

    // poziția cronologică: înaintea primului rând cu dată mai mare
    let row = last + 1;
    for (let i = 0; i <= last - FIRST_ROW; i++) {
      const v = colB[i][0];
      if (v instanceof Date && v.getTime() > date.getTime()) { row = FIRST_ROW + i; break; }
    }
    if (row <= last) sh.insertRowBefore(row);

    const vals = [date, p.document || '', p.partner || '', p.category || '', '', '', '', ''];
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
