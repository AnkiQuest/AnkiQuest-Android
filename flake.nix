{
  description = "AnkiQuest for Android, a fork of AnkiDroid";

  # The JBR, Android SDK and Node come from AnkiQuest-Dev, where every
  # AnkiQuest repository's toolchain is pinned. Its android-app shell builds
  # the app; its android shell adds the NDK and Rust for building the backend
  # (`nix develop github:AnkiQuest/AnkiQuest-Dev#android`).
  inputs.ankiquest-dev.url = "github:AnkiQuest/AnkiQuest-Dev";

  outputs = {
    self,
    ankiquest-dev,
    ...
  }: {
    inherit (ankiquest-dev) formatter;

    devShells = builtins.mapAttrs (_: shells: {default = shells.android-app;}) ankiquest-dev.devShells;

    checks = ankiquest-dev.lib.formatChecks self;
  };
}
