import { describe, expect, test } from "vitest";
import { normalizeDuration } from "./time-entry-duration-input";
import "./index";

describe("normalizeDuration", () => {
  test.each([
    // hours only
    ["8", "08:00"],
    ["08", "08:00"],
    ["12", "12:00"],
    ["0", "00:00"],
    // digits only — last two digits are the minutes
    ["830", "08:30"],
    ["0830", "08:30"],
    ["1230", "12:30"],
    ["030", "00:30"],
    // explicit colon, including single-digit hour and omitted parts
    ["8:30", "08:30"],
    ["08:30", "08:30"],
    ["8:3", "08:03"],
    ["8:", "08:00"],
    [":30", "00:30"],
    // decimal hours with dot or comma
    ["8.5", "08:30"],
    ["8,5", "08:30"],
    ["1,25", "01:15"],
    ["0,5", "00:30"],
    [".5", "00:30"],
    // surrounding whitespace is ignored
    ["  8:30  ", "08:30"],
  ])("normalizes %j to %j", (input, expected) => {
    expect(normalizeDuration(input)).toEqual(expected);
  });

  test.each([
    ["", ""],
    ["abc", "abc"],
    ["12345", "12345"], // too many digits to be HH:mm
    ["8:99", "8:99"], // minutes out of range
    ["99:99", "99:99"], // minutes out of range
  ])("leaves %j unchanged", (input, expected) => {
    expect(normalizeDuration(input)).toEqual(expected);
  });
});

describe("TimeEntryDurationInput", () => {
  test("normalizes a digits-only value on blur", () => {
    document.body.innerHTML = `<input type="text" is="z-time-entry-duration-input">`;
    const sut = document.querySelector("input")!;

    sut.value = "830";
    sut.dispatchEvent(new FocusEvent("blur"));

    expect(sut.value).toEqual("08:30");
  });

  test("normalizes decimal hours on blur", () => {
    document.body.innerHTML = `<input type="text" is="z-time-entry-duration-input">`;
    const sut = document.querySelector("input")!;

    sut.value = "8,5";
    sut.dispatchEvent(new FocusEvent("blur"));

    expect(sut.value).toEqual("08:30");
  });

  test("leaves an already canonical value untouched on blur", () => {
    document.body.innerHTML = `<input type="text" is="z-time-entry-duration-input">`;
    const sut = document.querySelector("input")!;

    sut.value = "08:30";
    sut.dispatchEvent(new FocusEvent("blur"));

    expect(sut.value).toEqual("08:30");
  });
});
