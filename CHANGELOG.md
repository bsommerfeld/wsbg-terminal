## Changelog

### Added

- Native OS window chrome on Windows/Linux: real min/max/close buttons, native drag, native edge-resize and Aero snap. On Windows the title bar is themed dark via DWM (`DWMWA_USE_IMMERSIVE_DARK_MODE`, JNA).

### Changed

- The HTML titlebar is now only used on macOS (over the native traffic lights). On Windows/Linux the native OS title bar is used instead and the theme toggle relocates to the footer.

### Fixed

- Titlebar layout on Windows/Linux: the app title was pushed left and the theme toggle dropped to a second row because `.tb-title` was left auto-placed in the grid. All titlebar columns are now pinned explicitly, keeping the title centered.

### Removed

- Emulated JS window drag/resize (`resize.js` + titlebar drag forwarding), obsolete now that every platform uses native window chrome.

