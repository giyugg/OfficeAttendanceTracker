# Reliable AutoMoto Attendance Tracker

A Java desktop application that reads an **existing QR code containing only an employee number**, looks up that employee in a local SQLite database, and alternates between time-in and time-out.

## Run on Windows

1. Install **Java 21 or newer**. A JDK is required to build from source; a Java runtime is enough for the packaged JAR.
2. Double-click **`start.cmd`** in this folder. It builds the application if the JAR is missing. The first build needs internet access; Maven is downloaded automatically.
3. Open **Employees → Add employee**. Enter the employee number exactly as encoded in the existing QR code and enter their name. The database starts empty.
4. Return to **Attendance station** and choose an input method:
   - **Webcam:** click **Find cameras**, select the camera, and click **Start camera**. Present one QR code at a time, wait for the confirmation, then remove the code.
   - **USB QR scanner:** configure it as a keyboard scanner with an **Enter** suffix. Click the employee-number field, then scan the code. You can also enter the employee number manually and press Enter.
5. The first valid scan records **time-in**. The next valid scan records **time-out**. A later scan starts another shift.

To run an already built application from a terminal:

```powershell
java -jar target/attendance-tracker.jar
```

On Linux/macOS, run `sh ./start.sh`. Camera support depends on your OS and native camera driver; a USB keyboard scanner uses ordinary text input and does not need webcam drivers.

## Attendance behavior

- Employee numbers are **text**, so `000123` and `123` are different employees. Matching is case-sensitive. Leading/trailing whitespace is trimmed; internal characters are matched exactly. Numbers can contain letters or hyphens.
- QR contents must be the employee number itself, without JSON, a URL, or other fields. No employee QR generation is included.
- An unknown, inactive, blank, malformed, or oversized employee number produces feedback without creating an attendance record.
- A **10-second cooldown per employee** prevents an accidental repeat scan from recording time-out immediately. Ignored scans do not restart the cooldown.
- A QR continuously visible to the webcam is delivered once. Remove it for at least **1.5 seconds** before presenting it again. Other employees can scan immediately.
- Time-out closes that employee's open shift, including a shift started before midnight or before an application restart. There is no automatic midnight time-out. If someone forgets to scan out, their next scan closes the existing shift.
- Multiple shifts per day are supported. Each employee can have only one open shift. Database transactions prevent simultaneous scans from creating duplicate open shifts.
- Inactive employees cannot scan. Reactivate an employee to close an outstanding shift. Editing a name also changes the name shown on historical records, which link to the employee directory.
- **Today** and **Selected date** filter by the shift's **time-in date**. **Currently in** includes all open shifts, including overnight shifts. The date field uses `YYYY-MM-DD`.
- **Export CSV** exports the rows currently visible in attendance history, including the employee search filter and table sort order. Times include a UTC offset. When opening a CSV in Excel, import the employee-number column as **Text** to keep leading zeros.

## Database and time settings

The app creates **`data/attendance.db`** automatically. The launch scripts always run from the project folder. If you launch the JAR directly, the default data path is relative to your terminal's working directory. Use an absolute `--db` path when a fixed location is needed:

```powershell
java -jar target/attendance-tracker.jar "--db=C:\Attendance\attendance.db" --zone=Asia/Manila
```

The default display timezone is **Asia/Manila**. All timestamps are stored as UTC epoch milliseconds using the computer's clock. `--zone` accepts Java timezone names such as `Asia/Manila`, `UTC`, or `America/New_York` and changes date filtering and display, without changing stored timestamps.

Database check without opening the desktop window:

```powershell
java -jar target/attendance-tracker.jar --check
```

This initializes the database if needed and reports its location and row counts.

Schema:

```sql
employees (
    employee_number TEXT PRIMARY KEY,
    full_name TEXT NOT NULL,
    active INTEGER NOT NULL DEFAULT 1
)
attendance (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    employee_number TEXT NOT NULL REFERENCES employees(employee_number),
    time_in INTEGER NOT NULL,
    time_out INTEGER NULL
)
```

The schema also enforces valid active values, time-out at or after time-in, and a unique open shift per employee. Queries use parameter binding. Existing employee data can be loaded into this schema using SQLite tools; this application does not connect to an unspecified external employee database.

Close the app before copying `data/attendance.db` for backup. This is a **local attendance station**: the employee directory and manual input are available to whoever can access the desktop. It does not include administrator sign-in, payroll rules, or multi-site server synchronization.

## Build and test

```powershell
.\mvnw.cmd -B -ntp verify
```

```sh
sh ./mvnw -B -ntp verify
```

Maven produces **`target/attendance-tracker.jar`**, including runtime dependencies. Rebuild after source changes; `start.cmd`/`start.sh` reuse an existing JAR. You can copy the JAR to another computer with Java 21+ and run it without Maven or internet access.

Automated tests cover QR image decoding through employee lookup, time-in/out alternation, persistence, overnight shifts, cooldown boundaries, simultaneous scans, database constraints, invalid/unknown/inactive employees, exact employee-number matching, date filtering, CSV export, and configuration. Physical webcam and USB scanner checks should also be performed on the intended attendance computer.

Source layout:

- `AttendanceDatabase` / `AttendanceService`: employee lookup and transactional attendance rules.
- `QrDecoder` / `QrPresenceGate` / `CameraScanner`: QR decoding, repeated-frame protection, and camera lifecycle.
- `AttendanceFrame`: Swing interface and background database work.
- `CsvExporter`: UTF-8 export with spreadsheet-formula protection.

Dependencies: [SQLite JDBC](https://github.com/xerial/sqlite-jdbc), [ZXing](https://github.com/zxing/zxing), and [Webcam Capture](https://github.com/sarxos/webcam-capture). The app uses Java Swing, so no web server or browser is required.
