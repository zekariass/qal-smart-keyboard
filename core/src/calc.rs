//! Safe arithmetic expression evaluator for the keyboard's quick calculator (T8.3).
//!
//! A hand-written recursive-descent parser over `f64`; no `eval`, no I/O, no logging, and no error
//! carries input text (N2). Deterministic: results are formatted to 12 significant digits (see
//! [`format_number`]), which hides binary float noise (`0.1 + 0.2` is `0.3`).
//!
//! Accepted syntax:
//! * `+ - * /` plus the keyboard forms `× ÷ x X − –` and parentheses; unary `-` and `+`.
//! * Decimals (`.` or the Arabic decimal separator), thousands separators (`,` and U+066C) ignored,
//!   Arabic-Indic, Extended Arabic-Indic and Ethiopic digits.
//! * `^` exponent (right associative; `-2^2` is `-4`).
//! * Percent: `50%` is `0.5`. After `+` or `-` a percent is a percent of the left operand, like a
//!   phone calculator (`200 + 10%` is `220`). `20% of 150` is `30`.
//!
//! Limits: [`MAX_EXPR_CHARS`] characters and [`MAX_DEPTH`] nesting levels.

use crate::calendar::{ethiopic_to_arabic, is_ethiopic_numeral};

/// Longest expression accepted, in characters.
pub const MAX_EXPR_CHARS: usize = 256;
/// Deepest nesting (parentheses, unary signs, exponents) accepted.
pub const MAX_DEPTH: usize = 64;

/// Why an expression could not be evaluated. Messages are fixed and never contain input text.
#[derive(Debug, Clone, Copy, PartialEq, Eq, thiserror::Error, uniffi::Error)]
pub enum CalcError {
    #[error("expression is empty")]
    Empty,
    #[error("expression is longer than the limit")]
    TooLong,
    #[error("expression is nested too deeply")]
    TooDeep,
    #[error("not a valid expression")]
    Syntax,
    #[error("division by zero")]
    DivisionByZero,
    #[error("result is too large")]
    Overflow,
    #[error("result is not a real number")]
    Domain,
}

#[derive(Clone, Copy, PartialEq, Debug)]
enum Tok {
    Num(f64),
    Plus,
    Minus,
    Star,
    Slash,
    Caret,
    Pct,
    Of,
    LParen,
    RParen,
}

fn digit_value(c: char) -> Option<u32> {
    match c {
        '0'..='9' => c.to_digit(10),
        '\u{0660}'..='\u{0669}' => Some(c as u32 - 0x0660),
        '\u{06F0}'..='\u{06F9}' => Some(c as u32 - 0x06F0),
        _ => None,
    }
}

fn is_dot(c: char) -> bool {
    c == '.' || c == '\u{066B}'
}

fn is_group_sep(c: char) -> bool {
    c == ',' || c == '\u{066C}'
}

fn tokenize(src: &str) -> Result<Vec<Tok>, CalcError> {
    let chars: Vec<char> = src.chars().collect();
    let mut out = Vec::new();
    let mut i = 0;
    while i < chars.len() {
        let c = chars[i];
        if c.is_whitespace() {
            i += 1;
        } else if digit_value(c).is_some() || is_dot(c) {
            // A number: digits with at most one dot; group separators after a digit are skipped.
            let mut text = String::new();
            let mut dot = false;
            while i < chars.len() {
                let ch = chars[i];
                if let Some(v) = digit_value(ch) {
                    text.push(char::from_digit(v, 10).unwrap_or('0'));
                } else if is_dot(ch) {
                    if dot {
                        return Err(CalcError::Syntax);
                    }
                    dot = true;
                    text.push('.');
                } else if is_group_sep(ch) && i > 0 && digit_value(chars[i - 1]).is_some() {
                    // ignored
                } else {
                    break;
                }
                i += 1;
            }
            if text == "." {
                return Err(CalcError::Syntax);
            }
            let v: f64 = text.parse().map_err(|_| CalcError::Syntax)?;
            if !v.is_finite() {
                return Err(CalcError::Overflow);
            }
            out.push(Tok::Num(v));
        } else if is_ethiopic_numeral(c) {
            let start = i;
            while i < chars.len() && is_ethiopic_numeral(chars[i]) {
                i += 1;
            }
            let run: String = chars[start..i].iter().collect();
            let v = ethiopic_to_arabic(run).map_err(|_| CalcError::Syntax)?;
            out.push(Tok::Num(v as f64));
        } else {
            i += 1;
            out.push(match c {
                '+' => Tok::Plus,
                '-' | '\u{2212}' | '\u{2013}' => Tok::Minus,
                '*' | 'x' | 'X' | '\u{00D7}' => Tok::Star,
                '/' | '\u{00F7}' => Tok::Slash,
                '^' => Tok::Caret,
                '%' | '\u{066A}' => Tok::Pct,
                '(' => Tok::LParen,
                ')' => Tok::RParen,
                'o' | 'O' if matches!(chars.get(i), Some('f' | 'F')) => {
                    i += 1;
                    Tok::Of
                }
                _ => return Err(CalcError::Syntax),
            });
        }
    }
    Ok(out)
}

/// A parsed value; `pct` is true when it ended in a postfix percent (`10%`).
#[derive(Clone, Copy)]
struct Val {
    v: f64,
    pct: bool,
}

struct Parser {
    toks: Vec<Tok>,
    pos: usize,
    depth: usize,
}

fn checked(v: f64) -> Result<f64, CalcError> {
    if v.is_nan() {
        Err(CalcError::Domain)
    } else if v.is_infinite() {
        Err(CalcError::Overflow)
    } else {
        Ok(v)
    }
}

impl Parser {
    fn peek(&self) -> Option<Tok> {
        self.toks.get(self.pos).copied()
    }

    fn enter(&mut self) -> Result<(), CalcError> {
        self.depth += 1;
        if self.depth > MAX_DEPTH {
            Err(CalcError::TooDeep)
        } else {
            Ok(())
        }
    }

    fn expr(&mut self) -> Result<Val, CalcError> {
        let mut left = self.term()?;
        while let Some(op @ (Tok::Plus | Tok::Minus)) = self.peek() {
            self.pos += 1;
            let right = self.term()?;
            // Phone-calculator percent: `200 + 10%` adds 10% of 200.
            let r = if right.pct { left.v * right.v } else { right.v };
            let v = if op == Tok::Plus {
                left.v + r
            } else {
                left.v - r
            };
            left = Val {
                v: checked(v)?,
                pct: false,
            };
        }
        Ok(left)
    }

    fn term(&mut self) -> Result<Val, CalcError> {
        let mut left = self.unary()?;
        while let Some(op @ (Tok::Star | Tok::Slash | Tok::Of)) = self.peek() {
            self.pos += 1;
            let right = self.unary()?;
            let v = if op == Tok::Slash {
                if right.v == 0.0 {
                    return Err(CalcError::DivisionByZero);
                }
                left.v / right.v
            } else {
                left.v * right.v
            };
            left = Val {
                v: checked(v)?,
                pct: false,
            };
        }
        Ok(left)
    }

    fn unary(&mut self) -> Result<Val, CalcError> {
        match self.peek() {
            Some(Tok::Minus) => {
                self.pos += 1;
                self.enter()?;
                let r = self.unary();
                self.depth -= 1;
                let v = r?;
                Ok(Val {
                    v: -v.v,
                    pct: v.pct,
                })
            }
            Some(Tok::Plus) => {
                self.pos += 1;
                self.enter()?;
                let r = self.unary();
                self.depth -= 1;
                r
            }
            _ => self.power(),
        }
    }

    fn power(&mut self) -> Result<Val, CalcError> {
        let base = self.postfix()?;
        if self.peek() == Some(Tok::Caret) {
            self.pos += 1;
            self.enter()?;
            let exp = self.unary(); // right associative, allows `2^-1`
            self.depth -= 1;
            let exp = exp?;
            if base.v == 0.0 && exp.v < 0.0 {
                return Err(CalcError::DivisionByZero);
            }
            let v = base.v.powf(exp.v);
            return Ok(Val {
                v: checked(v)?,
                pct: false,
            });
        }
        Ok(base)
    }

    fn postfix(&mut self) -> Result<Val, CalcError> {
        let mut val = self.primary()?;
        while self.peek() == Some(Tok::Pct) {
            self.pos += 1;
            val = Val {
                v: val.v / 100.0,
                pct: true,
            };
        }
        Ok(val)
    }

    fn primary(&mut self) -> Result<Val, CalcError> {
        match self.peek() {
            Some(Tok::Num(v)) => {
                self.pos += 1;
                Ok(Val { v, pct: false })
            }
            Some(Tok::LParen) => {
                self.pos += 1;
                self.enter()?;
                let inner = self.expr();
                self.depth -= 1;
                let inner = inner?;
                if self.peek() != Some(Tok::RParen) {
                    return Err(CalcError::Syntax);
                }
                self.pos += 1;
                Ok(Val {
                    v: inner.v,
                    pct: false,
                })
            }
            _ => Err(CalcError::Syntax),
        }
    }
}

/// Evaluates `expr` to a number. See the module docs for the syntax.
pub fn evaluate(expr: &str) -> Result<f64, CalcError> {
    if expr.chars().count() > MAX_EXPR_CHARS {
        return Err(CalcError::TooLong);
    }
    let toks = tokenize(expr)?;
    if toks.is_empty() {
        return Err(CalcError::Empty);
    }
    let mut p = Parser {
        toks,
        pos: 0,
        depth: 0,
    };
    let v = p.expr()?;
    if p.pos != p.toks.len() {
        return Err(CalcError::Syntax);
    }
    checked(v.v)
}

/// Formats `x` with at most 12 significant digits: plain decimal for magnitudes from 1e-7 up to
/// (excluding) 1e15, `1.5E+20` style beyond; trailing zeros dropped; negative zero is `0`.
pub fn format_number(x: f64) -> String {
    if x == 0.0 {
        return "0".to_string();
    }
    let sci = format!("{:.11e}", x.abs()); // d.ddddddddddde<exp>
    let (mant, exp) = sci.split_once('e').unwrap_or((&sci, "0"));
    let exp: i32 = exp.parse().unwrap_or(0);
    let mut digits: String = mant.chars().filter(|c| *c != '.').collect();
    while digits.len() > 1 && digits.ends_with('0') {
        digits.pop();
    }
    let sign = if x < 0.0 { "-" } else { "" };
    let body = if (-7..15).contains(&exp) {
        if exp >= 0 {
            let int_len = exp as usize + 1;
            if digits.len() <= int_len {
                format!("{digits}{}", "0".repeat(int_len - digits.len()))
            } else {
                format!("{}.{}", &digits[..int_len], &digits[int_len..])
            }
        } else {
            format!("0.{}{digits}", "0".repeat((-exp - 1) as usize))
        }
    } else {
        let (first, rest) = digits.split_at(1);
        let m = if rest.is_empty() {
            first.to_string()
        } else {
            format!("{first}.{rest}")
        };
        format!("{m}E{}{}", if exp < 0 { "-" } else { "+" }, exp.abs())
    };
    format!("{sign}{body}")
}

/// Evaluates `expr` and formats the result ([`format_number`]).
#[uniffi::export]
pub fn calc_eval(expr: String) -> Result<String, CalcError> {
    evaluate(&expr).map(format_number)
}

#[cfg(test)]
mod tests {
    use super::*;
    use proptest::prelude::*;

    fn ev(s: &str) -> String {
        calc_eval(s.to_string()).unwrap_or_else(|e| format!("ERR {e:?}"))
    }

    #[test]
    fn arithmetic_table() {
        let cases = [
            ("1+2", "3"),
            ("2+3*4", "14"),
            ("(2+3)*4", "20"),
            ("10-4-3", "3"),
            ("100/8", "12.5"),
            ("7 × 6", "42"),
            ("42 ÷ 6", "7"),
            ("3 x 4", "12"),
            ("3 X 4", "12"),
            ("5 − 8", "-3"),
            ("-5+2", "-3"),
            ("--5", "5"),
            ("+5", "5"),
            ("0.1+0.2", "0.3"),
            (".5+.5", "1"),
            ("5.", "5"),
            ("1/3", "0.333333333333"),
            ("2/3", "0.666666666667"),
            ("1,234,567 + 1", "1234568"),
            ("2^10", "1024"),
            ("2^3^2", "512"),
            ("-2^2", "-4"),
            ("2^-1", "0.5"),
            ("4^0.5", "2"),
            ("((((1))))", "1"),
            ("1000000*1000000*1000", "1E+15"),
            ("123456789012", "123456789012"),
            ("0.00000001", "1E-8"),
            ("0.0000001", "0.0000001"),
            ("-0", "0"),
            ("0*-1", "0"),
        ];
        for (src, want) in cases {
            assert_eq!(ev(src), want, "{src}");
        }
    }

    #[test]
    fn percent_follows_phone_calculators() {
        let cases = [
            ("50%", "0.5"),
            ("200+10%", "220"),
            ("200-10%", "180"),
            ("200*10%", "20"),
            ("20% of 150", "30"),
            ("20%of150", "30"),
            ("50%%", "0.005"),
            ("(200+10%)+10%", "242"),
            ("100+50%*2", "101"),
            ("1+2+10%", "3.3"),
        ];
        for (src, want) in cases {
            assert_eq!(ev(src), want, "{src}");
        }
    }

    #[test]
    fn digits_of_other_scripts() {
        assert_eq!(ev("\u{0661}\u{0662} + \u{0663}"), "15"); // Arabic-Indic 12 + 3
        assert_eq!(ev("\u{06F4}\u{06F2}"), "42");
        assert_eq!(ev("\u{0661}\u{066B}\u{0665} * 2"), "3");
        assert_eq!(ev("፭ + ፮"), "11");
        assert_eq!(ev("፳፭ × 2"), "50");
        assert_eq!(ev("፻ ÷ ፬"), "25");
    }

    #[test]
    fn typed_errors() {
        assert_eq!(calc_eval(String::new()), Err(CalcError::Empty));
        assert_eq!(calc_eval("   ".into()), Err(CalcError::Empty));
        assert_eq!(calc_eval("1/0".into()), Err(CalcError::DivisionByZero));
        assert_eq!(calc_eval("1/(2-2)".into()), Err(CalcError::DivisionByZero));
        assert_eq!(calc_eval("0^-1".into()), Err(CalcError::DivisionByZero));
        assert_eq!(calc_eval("(-8)^0.5".into()), Err(CalcError::Domain));
        assert_eq!(calc_eval("10^400".into()), Err(CalcError::Overflow));
        assert_eq!(calc_eval("9^9^9".into()), Err(CalcError::Overflow));
        for bad in [
            "1+", "*2", "(1", "1)", "()", "1 2", "1..2", ".", "abc", "1+*2", "2 of", "1;2", "1e5",
            "$5",
        ] {
            assert_eq!(calc_eval(bad.into()), Err(CalcError::Syntax), "{bad}");
        }
        let long = "1+".repeat(200) + "1";
        assert_eq!(calc_eval(long), Err(CalcError::TooLong));
        let deep = "(".repeat(100) + "1" + &")".repeat(100);
        assert_eq!(calc_eval(deep), Err(CalcError::TooDeep));
        assert_eq!(calc_eval("-".repeat(200) + "1"), Err(CalcError::TooDeep));
        let ok = "1+".repeat(127) + "1";
        assert_eq!(ev(&ok), "128");
        assert_eq!(calc_eval("1".repeat(300)), Err(CalcError::TooLong));
        assert_eq!(
            calc_eval("(".to_string() + &"9".repeat(120) + ")^3"),
            Err(CalcError::Overflow)
        );
    }

    #[test]
    fn error_messages_never_contain_input() {
        let secret = "hunter2";
        for e in [
            CalcError::Empty,
            CalcError::TooLong,
            CalcError::TooDeep,
            CalcError::Syntax,
            CalcError::DivisionByZero,
            CalcError::Overflow,
            CalcError::Domain,
        ] {
            assert!(!e.to_string().contains(secret));
        }
        let err = calc_eval(format!("{secret}+1")).unwrap_err();
        assert!(!format!("{err} {err:?}").contains(secret));
    }

    #[test]
    fn format_edge_cases() {
        assert_eq!(format_number(0.5), "0.5");
        assert_eq!(format_number(-1234.5678), "-1234.5678");
        assert_eq!(format_number(999_999_999_999_999.0), "1E+15");
        assert_eq!(format_number(123_456_789_012_345.0), "123456789012000");
        assert_eq!(format_number(1.5e20), "1.5E+20");
        assert_eq!(format_number(-2.5e-10), "-2.5E-10");
        assert_eq!(format_number(100.0), "100");
    }

    proptest! {
        #![proptest_config(ProptestConfig { cases: 512, ..ProptestConfig::default() })]

        #[test]
        fn never_panics_on_arbitrary_text(s in ".{0,300}") {
            let _ = calc_eval(s);
        }

        #[test]
        fn never_panics_on_expression_alphabet(s in "[0-9+\\-*/^%().,x×÷ ofOF]{0,300}") {
            let _ = calc_eval(s);
        }

        #[test]
        fn integer_sums_and_products_are_exact(a in -1_000_000i64..1_000_000, b in -1_000_000i64..1_000_000) {
            prop_assert_eq!(calc_eval(format!("{a}+({b})")).unwrap(), (a + b).to_string());
            prop_assert_eq!(calc_eval(format!("({a})*({b})")).unwrap(), (a * b).to_string());
        }

        #[test]
        fn formatted_results_reparse_to_themselves(a in -1_000_000i64..1_000_000, b in 1i64..1000) {
            let once = calc_eval(format!("({a})/{b}")).unwrap();
            let twice = calc_eval(once.clone()).unwrap();
            prop_assert_eq!(once, twice);
        }
    }
}
