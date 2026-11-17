//! Ethiopian calendar (Amete Mihret), Ethiopian clock (6:00 offset) and Ethiopic/Arabic numeral
//! conversion (C1.9).
//!
//! Pure functions over plain owned values; no I/O, no logging, no user content (N2). Dates are
//! converted through the Julian Day Number so Gregorian and Ethiopian dates round-trip exactly.

/// Supported year range (both calendars), which keeps all arithmetic far from overflow.
pub const MIN_YEAR: i32 = 1;
pub const MAX_YEAR: i32 = 9999;
/// Largest number the Ethiopic numeral encoder supports (below 10^8, i.e. two myriad groups).
pub const MAX_NUMERAL: u64 = 99_999_999;

/// Era offset of the Amete Mihret calendar: Ethiopian 1/1/1 is Julian Day Number 1_724_221, which
/// equals this constant plus `365 * 1 + 1 / 4 + 30 + 1 - 31`.
const ETHIOPIC_EPOCH_JDN: i64 = 1_723_856;

/// Errors from calendar, clock and numeral conversion. Messages never contain input text.
#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error, uniffi::Error)]
pub enum CalendarError {
    #[error("date is not valid in this calendar")]
    InvalidDate,
    #[error("time is not valid")]
    InvalidTime,
    #[error("year is outside the supported range")]
    YearOutOfRange,
    #[error("Ethiopic numerals cannot represent zero")]
    ZeroNotRepresentable,
    #[error("number is too large for Ethiopic numerals")]
    NumberTooLarge,
    #[error("not a valid Ethiopic numeral")]
    InvalidNumeral,
}

/// A date in the Ethiopian calendar: month 1..=13 (13 is Pagume), day 1..=30 (Pagume 1..=5/6).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, uniffi::Record)]
pub struct EthiopianDate {
    pub year: i32,
    pub month: u32,
    pub day: u32,
}

/// A date in the proleptic Gregorian calendar.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, uniffi::Record)]
pub struct GregorianDate {
    pub year: i32,
    pub month: u32,
    pub day: u32,
}

/// Day (6:00-17:59) or night (18:00-5:59) half of the Ethiopian clock.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, uniffi::Enum)]
pub enum EthiopianPeriod {
    Day,
    Night,
}

/// Time on the 24-hour clock.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, uniffi::Record)]
pub struct ClockTime {
    pub hour: u32,
    pub minute: u32,
}

/// Time on the Ethiopian 12-hour clock, whose hour 12 starts at 6:00 / 18:00.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, uniffi::Record)]
pub struct EthiopianTime {
    /// 1..=12.
    pub hour: u32,
    pub minute: u32,
    pub period: EthiopianPeriod,
}

// ---------------------------------------------------------------------------------------------
// Calendar
// ---------------------------------------------------------------------------------------------

/// Ethiopian leap years are those with `year % 4 == 3`; they give Pagume 6 days.
#[uniffi::export]
pub fn is_ethiopian_leap_year(year: i32) -> bool {
    year.rem_euclid(4) == 3
}

/// Gregorian leap-year rule.
#[uniffi::export]
pub fn is_gregorian_leap_year(year: i32) -> bool {
    (year % 4 == 0 && year % 100 != 0) || year % 400 == 0
}

/// Days in an Ethiopian month, or 0 when `month` is not 1..=13.
#[uniffi::export]
pub fn ethiopian_days_in_month(year: i32, month: u32) -> u32 {
    match month {
        1..=12 => 30,
        13 if is_ethiopian_leap_year(year) => 6,
        13 => 5,
        _ => 0,
    }
}

/// Amharic month name (1..=13, 13 = Pagume), or `None` for other values.
#[uniffi::export]
pub fn ethiopian_month_name(month: u32) -> Option<String> {
    const NAMES: [&str; 13] = [
        "መስከረም",
        "ጥቅምት",
        "ኅዳር",
        "ታኅሣሥ",
        "ጥር",
        "የካቲት",
        "መጋቢት",
        "ሚያዝያ",
        "ግንቦት",
        "ሰኔ",
        "ሐምሌ",
        "ነሐሴ",
        "ጳጉሜን",
    ];
    NAMES
        .get((month as usize).checked_sub(1)?)
        .map(|s| s.to_string())
}

fn gregorian_days_in_month(year: i32, month: u32) -> u32 {
    match month {
        1 | 3 | 5 | 7 | 8 | 10 | 12 => 31,
        4 | 6 | 9 | 11 => 30,
        2 if is_gregorian_leap_year(year) => 29,
        2 => 28,
        _ => 0,
    }
}

fn gregorian_to_jdn(d: GregorianDate) -> i64 {
    let (y, m, day) = (d.year as i64, d.month as i64, d.day as i64);
    let a = (14 - m) / 12;
    let yy = y + 4800 - a;
    let mm = m + 12 * a - 3;
    day + (153 * mm + 2) / 5 + 365 * yy + yy / 4 - yy / 100 + yy / 400 - 32045
}

fn jdn_to_gregorian(jdn: i64) -> GregorianDate {
    let a = jdn + 32044;
    let b = (4 * a + 3) / 146_097;
    let c = a - 146_097 * b / 4;
    let d = (4 * c + 3) / 1461;
    let e = c - 1461 * d / 4;
    let m = (5 * e + 2) / 153;
    GregorianDate {
        year: (100 * b + d - 4800 + m / 10) as i32,
        month: (m + 3 - 12 * (m / 10)) as u32,
        day: (e - (153 * m + 2) / 5 + 1) as u32,
    }
}

fn ethiopian_to_jdn(d: EthiopianDate) -> i64 {
    let y = d.year as i64;
    ETHIOPIC_EPOCH_JDN + 365 * y + y.div_euclid(4) + 30 * d.month as i64 + d.day as i64 - 31
}

fn jdn_to_ethiopian(jdn: i64) -> EthiopianDate {
    let since = jdn - ETHIOPIC_EPOCH_JDN;
    let r = since.rem_euclid(1461);
    let n = r % 365 + 365 * (r / 1460);
    EthiopianDate {
        year: (4 * since.div_euclid(1461) + r / 365 - r / 1460) as i32,
        month: (n / 30 + 1) as u32,
        day: (n % 30 + 1) as u32,
    }
}

fn validate_gregorian(d: GregorianDate) -> Result<(), CalendarError> {
    if !(MIN_YEAR..=MAX_YEAR).contains(&d.year) {
        return Err(CalendarError::YearOutOfRange);
    }
    if d.day < 1 || d.day > gregorian_days_in_month(d.year, d.month) {
        return Err(CalendarError::InvalidDate);
    }
    Ok(())
}

fn validate_ethiopian(d: EthiopianDate) -> Result<(), CalendarError> {
    if !(MIN_YEAR..=MAX_YEAR).contains(&d.year) {
        return Err(CalendarError::YearOutOfRange);
    }
    if d.day < 1 || d.day > ethiopian_days_in_month(d.year, d.month) {
        return Err(CalendarError::InvalidDate);
    }
    Ok(())
}

/// Converts a Gregorian date (years 1..=9999) to the Ethiopian calendar.
#[uniffi::export]
pub fn gregorian_to_ethiopian(date: GregorianDate) -> Result<EthiopianDate, CalendarError> {
    validate_gregorian(date)?;
    let eth = jdn_to_ethiopian(gregorian_to_jdn(date));
    if eth.year < MIN_YEAR {
        return Err(CalendarError::YearOutOfRange);
    }
    Ok(eth)
}

/// Converts an Ethiopian date (years 1..=9999) to the Gregorian calendar.
#[uniffi::export]
pub fn ethiopian_to_gregorian(date: EthiopianDate) -> Result<GregorianDate, CalendarError> {
    validate_ethiopian(date)?;
    Ok(jdn_to_gregorian(ethiopian_to_jdn(date)))
}

// ---------------------------------------------------------------------------------------------
// Clock
// ---------------------------------------------------------------------------------------------

/// Converts 24-hour time to Ethiopian time: 6:00 is 12:00 (day), 7:00 is 1:00, 18:00 is 12:00
/// (night), 0:00 is 6:00 (night).
#[uniffi::export]
pub fn clock_to_ethiopian(time: ClockTime) -> Result<EthiopianTime, CalendarError> {
    if time.hour > 23 || time.minute > 59 {
        return Err(CalendarError::InvalidTime);
    }
    let hour = match (time.hour + 6) % 12 {
        0 => 12,
        h => h,
    };
    let period = if (6..18).contains(&time.hour) {
        EthiopianPeriod::Day
    } else {
        EthiopianPeriod::Night
    };
    Ok(EthiopianTime {
        hour,
        minute: time.minute,
        period,
    })
}

/// Converts Ethiopian time back to 24-hour time.
#[uniffi::export]
pub fn ethiopian_to_clock(time: EthiopianTime) -> Result<ClockTime, CalendarError> {
    if !(1..=12).contains(&time.hour) || time.minute > 59 {
        return Err(CalendarError::InvalidTime);
    }
    // Ethiopian hours 6..=11 fall in the second half of their 12-hour period (the 24-hour
    // clock hour is 12..=17 for Day and 0..=5 for Night), so the PM offset flips for them.
    let second_half = (6..=11).contains(&time.hour);
    let is_night = matches!(time.period, EthiopianPeriod::Night);
    let offset = if is_night != second_half { 12 } else { 0 };
    Ok(ClockTime {
        hour: ((time.hour + 6) % 12 + offset) % 24,
        minute: time.minute,
    })
}

// ---------------------------------------------------------------------------------------------
// Numerals
// ---------------------------------------------------------------------------------------------

const ONES: [char; 9] = ['፩', '፪', '፫', '፬', '፭', '፮', '፯', '፰', '፱'];
const TENS: [char; 9] = ['፲', '፳', '፴', '፵', '፶', '፷', '፸', '፹', '፺'];
const HUNDRED: char = '፻';
const TEN_THOUSAND: char = '፼';

fn push_pair(out: &mut String, v: u64) {
    if v >= 10 {
        out.push(TENS[(v / 10 - 1) as usize]);
    }
    if !v.is_multiple_of(10) {
        out.push(ONES[(v % 10 - 1) as usize]);
    }
}

/// Encodes 1..=9999. A leading coefficient of 1 before the hundred sign is omitted.
fn push_group(out: &mut String, g: u64) {
    let (hi, lo) = (g / 100, g % 100);
    if hi > 0 {
        if hi != 1 {
            push_pair(out, hi);
        }
        out.push(HUNDRED);
    }
    push_pair(out, lo);
}

/// Converts a positive integer (up to 99,999,999) to Ethiopic numerals, e.g. 2017 becomes
/// twenty-hundred plus seventeen.
#[uniffi::export]
pub fn arabic_to_ethiopic(n: u64) -> Result<String, CalendarError> {
    if n == 0 {
        return Err(CalendarError::ZeroNotRepresentable);
    }
    if n > MAX_NUMERAL {
        return Err(CalendarError::NumberTooLarge);
    }
    let (high, low) = (n / 10_000, n % 10_000);
    let mut out = String::new();
    if high > 0 {
        if high != 1 {
            push_group(&mut out, high);
        }
        out.push(TEN_THOUSAND);
    }
    if low > 0 {
        push_group(&mut out, low);
    }
    Ok(out)
}

/// Converts Ethiopic numerals to an integer. Rejects empty input and any non-numeral character.
#[uniffi::export]
pub fn ethiopic_to_arabic(text: String) -> Result<u64, CalendarError> {
    let (mut total, mut group, mut pair) = (0u64, 0u64, 0u64);
    for c in text.chars() {
        if let Some(i) = ONES.iter().position(|&d| d == c) {
            pair += i as u64 + 1;
        } else if let Some(i) = TENS.iter().position(|&d| d == c) {
            pair += (i as u64 + 1) * 10;
        } else if c == HUNDRED {
            group += pair.max(1) * 100;
            pair = 0;
        } else if c == TEN_THOUSAND {
            total += (group + pair).max(1) * 10_000;
            group = 0;
            pair = 0;
        } else {
            return Err(CalendarError::InvalidNumeral);
        }
        if total > MAX_NUMERAL || group > MAX_NUMERAL {
            return Err(CalendarError::NumberTooLarge);
        }
    }
    let n = total + group + pair;
    if n == 0 {
        return Err(CalendarError::InvalidNumeral);
    }
    if n > MAX_NUMERAL {
        return Err(CalendarError::NumberTooLarge);
    }
    Ok(n)
}

#[cfg(test)]
mod tests {
    use super::*;
    use proptest::prelude::*;

    fn g(year: i32, month: u32, day: u32) -> GregorianDate {
        GregorianDate { year, month, day }
    }
    fn e(year: i32, month: u32, day: u32) -> EthiopianDate {
        EthiopianDate { year, month, day }
    }

    #[test]
    fn meskerem_1_falls_on_sept_11_or_12() {
        // Sept 12 in the Gregorian year after an Ethiopian leap year (Pagume of 6 days).
        assert_eq!(
            gregorian_to_ethiopian(g(2023, 9, 12)).unwrap(),
            e(2016, 1, 1)
        );
        assert_eq!(
            gregorian_to_ethiopian(g(2024, 9, 11)).unwrap(),
            e(2017, 1, 1)
        );
        assert_eq!(
            gregorian_to_ethiopian(g(2025, 9, 11)).unwrap(),
            e(2018, 1, 1)
        );
        assert_eq!(
            gregorian_to_ethiopian(g(2026, 9, 11)).unwrap(),
            e(2019, 1, 1)
        );
        assert_eq!(
            gregorian_to_ethiopian(g(2027, 9, 12)).unwrap(),
            e(2020, 1, 1)
        );
        assert_eq!(
            ethiopian_to_gregorian(e(2017, 1, 1)).unwrap(),
            g(2024, 9, 11)
        );
        assert_eq!(
            ethiopian_to_gregorian(e(2020, 1, 1)).unwrap(),
            g(2027, 9, 12)
        );
    }

    #[test]
    fn known_dates() {
        assert_eq!(
            gregorian_to_ethiopian(g(2000, 1, 1)).unwrap(),
            e(1992, 4, 22)
        );
        assert_eq!(
            gregorian_to_ethiopian(g(2024, 1, 7)).unwrap(),
            e(2016, 4, 28)
        );
        assert_eq!(
            gregorian_to_ethiopian(g(2025, 1, 7)).unwrap(),
            e(2017, 4, 29)
        ); // Genna
        assert_eq!(
            gregorian_to_ethiopian(g(2024, 9, 27)).unwrap(),
            e(2017, 1, 17)
        ); // Meskel
        assert_eq!(
            gregorian_to_ethiopian(g(1970, 1, 1)).unwrap(),
            e(1962, 4, 23)
        );
        assert_eq!(ethiopian_to_gregorian(e(1, 1, 1)).unwrap(), g(8, 8, 27));
    }

    #[test]
    fn pagume_length_and_rollover() {
        assert_eq!(ethiopian_days_in_month(2015, 13), 6);
        assert_eq!(ethiopian_days_in_month(2016, 13), 5);
        assert_eq!(ethiopian_days_in_month(2016, 12), 30);
        assert_eq!(ethiopian_days_in_month(2016, 14), 0);
        assert!(is_ethiopian_leap_year(2015) && !is_ethiopian_leap_year(2016));
        // Pagume 6 of the leap year 2015 is Sept 11 2023; the next day starts 2016.
        assert_eq!(
            ethiopian_to_gregorian(e(2015, 13, 6)).unwrap(),
            g(2023, 9, 11)
        );
        assert_eq!(
            ethiopian_to_gregorian(e(2015, 13, 5)).unwrap(),
            g(2023, 9, 10)
        );
        assert_eq!(
            ethiopian_to_gregorian(e(2016, 13, 5)).unwrap(),
            g(2024, 9, 10)
        );
        assert_eq!(
            gregorian_to_ethiopian(g(2024, 9, 10)).unwrap(),
            e(2016, 13, 5)
        );
    }

    #[test]
    fn invalid_dates_rejected() {
        assert_eq!(
            ethiopian_to_gregorian(e(2016, 13, 6)),
            Err(CalendarError::InvalidDate)
        );
        assert_eq!(
            ethiopian_to_gregorian(e(2016, 14, 1)),
            Err(CalendarError::InvalidDate)
        );
        assert_eq!(
            ethiopian_to_gregorian(e(2016, 0, 1)),
            Err(CalendarError::InvalidDate)
        );
        assert_eq!(
            ethiopian_to_gregorian(e(2016, 1, 31)),
            Err(CalendarError::InvalidDate)
        );
        assert_eq!(
            gregorian_to_ethiopian(g(2023, 2, 29)),
            Err(CalendarError::InvalidDate)
        );
        assert!(gregorian_to_ethiopian(g(2024, 2, 29)).is_ok());
        assert_eq!(
            gregorian_to_ethiopian(g(0, 1, 1)),
            Err(CalendarError::YearOutOfRange)
        );
        assert_eq!(
            gregorian_to_ethiopian(g(8, 1, 1)),
            Err(CalendarError::YearOutOfRange)
        );
        assert_eq!(
            ethiopian_to_gregorian(e(10000, 1, 1)),
            Err(CalendarError::YearOutOfRange)
        );
    }

    #[test]
    fn month_names() {
        assert_eq!(ethiopian_month_name(1).as_deref(), Some("መስከረም"));
        assert_eq!(ethiopian_month_name(13).as_deref(), Some("ጳጉሜን"));
        assert_eq!(ethiopian_month_name(0), None);
        assert_eq!(ethiopian_month_name(14), None);
    }

    #[test]
    fn clock_known_values() {
        let c = |hour, minute| clock_to_ethiopian(ClockTime { hour, minute }).unwrap();
        let eth = |hour, minute, period| EthiopianTime {
            hour,
            minute,
            period,
        };
        assert_eq!(c(6, 0), eth(12, 0, EthiopianPeriod::Day));
        assert_eq!(c(7, 30), eth(1, 30, EthiopianPeriod::Day));
        assert_eq!(c(12, 0), eth(6, 0, EthiopianPeriod::Day));
        assert_eq!(c(17, 59), eth(11, 59, EthiopianPeriod::Day));
        assert_eq!(c(18, 0), eth(12, 0, EthiopianPeriod::Night));
        assert_eq!(c(19, 0), eth(1, 0, EthiopianPeriod::Night));
        assert_eq!(c(0, 0), eth(6, 0, EthiopianPeriod::Night));
        assert_eq!(c(5, 59), eth(11, 59, EthiopianPeriod::Night));
        assert_eq!(
            clock_to_ethiopian(ClockTime {
                hour: 24,
                minute: 0
            }),
            Err(CalendarError::InvalidTime)
        );
        assert_eq!(
            ethiopian_to_clock(eth(0, 0, EthiopianPeriod::Day)),
            Err(CalendarError::InvalidTime)
        );
    }

    #[test]
    fn numeral_known_values() {
        let cases: [(u64, &str); 15] = [
            (1, "፩"),
            (9, "፱"),
            (10, "፲"),
            (11, "፲፩"),
            (99, "፺፱"),
            (100, "፻"),
            (101, "፻፩"),
            (200, "፪፻"),
            (1000, "፲፻"),
            (2017, "፳፻፲፯"),
            (10_000, "፼"),
            (10_100, "፼፻"),
            (20_000, "፪፼"),
            (200_000, "፳፼"),
            (1_000_000, "፻፼"),
        ];
        for (n, s) in cases {
            assert_eq!(arabic_to_ethiopic(n).unwrap(), s, "encode {n}");
            assert_eq!(ethiopic_to_arabic(s.to_string()).unwrap(), n, "decode {s}");
        }
        assert_eq!(
            arabic_to_ethiopic(0),
            Err(CalendarError::ZeroNotRepresentable)
        );
        assert_eq!(
            arabic_to_ethiopic(100_000_000),
            Err(CalendarError::NumberTooLarge)
        );
        assert_eq!(
            ethiopic_to_arabic(String::new()),
            Err(CalendarError::InvalidNumeral)
        );
        assert_eq!(
            ethiopic_to_arabic("፩a".into()),
            Err(CalendarError::InvalidNumeral)
        );
        assert_eq!(
            ethiopic_to_arabic("12".into()),
            Err(CalendarError::InvalidNumeral)
        );
    }

    proptest! {
        #[test]
        fn gregorian_ethiopian_roundtrip(year in 9i32..=9999, month in 1u32..=12, day in 1u32..=31) {
            prop_assume!(day <= gregorian_days_in_month(year, month));
            let gd = g(year, month, day);
            let ed = gregorian_to_ethiopian(gd).unwrap();
            prop_assert!(ed.month >= 1 && ed.month <= 13);
            prop_assert!(ed.day >= 1 && ed.day <= ethiopian_days_in_month(ed.year, ed.month));
            prop_assert_eq!(ethiopian_to_gregorian(ed).unwrap(), gd);
        }

        #[test]
        fn ethiopian_gregorian_roundtrip(year in 2i32..=9990, month in 1u32..=13, day in 1u32..=30) {
            prop_assume!(day <= ethiopian_days_in_month(year, month));
            let ed = e(year, month, day);
            let gd = ethiopian_to_gregorian(ed).unwrap();
            prop_assert_eq!(gregorian_to_ethiopian(gd).unwrap(), ed);
        }

        #[test]
        fn consecutive_days_are_consecutive(year in 9i32..=9990, month in 1u32..=12, day in 1u32..=28) {
            // The next Gregorian day maps to the next Ethiopian day (or the 1st of the next month).
            let gd = g(year, month, day);
            let next = jdn_to_gregorian(gregorian_to_jdn(gd) + 1);
            let (a, b) = (gregorian_to_ethiopian(gd).unwrap(), gregorian_to_ethiopian(next).unwrap());
            if a.day == ethiopian_days_in_month(a.year, a.month) {
                let (ny, nm) = if a.month == 13 { (a.year + 1, 1) } else { (a.year, a.month + 1) };
                prop_assert_eq!(b, e(ny, nm, 1));
            } else {
                prop_assert_eq!(b, e(a.year, a.month, a.day + 1));
            }
        }

        #[test]
        fn pagume_has_5_or_6_days(year in 1i32..=9999) {
            let d = ethiopian_days_in_month(year, 13);
            prop_assert_eq!(d, if year % 4 == 3 { 6 } else { 5 });
            prop_assert!(ethiopian_to_gregorian(e(year, 13, d)).is_ok());
            prop_assert!(ethiopian_to_gregorian(e(year, 13, d + 1)).is_err());
        }

        #[test]
        fn meskerem_1_is_sept_11_or_12(year in 1900i32..=2092) {
            // Valid while the Julian/Gregorian gap is 13 days (1900-03-01 to 2100-02-28).
            let gd = ethiopian_to_gregorian(e(year, 1, 1)).unwrap();
            let expected = if is_ethiopian_leap_year(year - 1) { 12 } else { 11 };
            prop_assert_eq!((gd.month, gd.day), (9, expected));
        }

        #[test]
        fn clock_roundtrip(hour in 0u32..24, minute in 0u32..60) {
            let t = ClockTime { hour, minute };
            let eth = clock_to_ethiopian(t).unwrap();
            prop_assert!((1..=12).contains(&eth.hour));
            prop_assert_eq!(ethiopian_to_clock(eth).unwrap(), t);
        }

        #[test]
        fn numeral_roundtrip(n in 1u64..=MAX_NUMERAL) {
            let s = arabic_to_ethiopic(n).unwrap();
            prop_assert_eq!(ethiopic_to_arabic(s).unwrap(), n);
        }

        #[test]
        fn decode_never_panics(s in "\\PC{0,12}") {
            let _ = ethiopic_to_arabic(s);
        }
    }
}
