{
  description = "AnkiQuest for Android, a fork of AnkiDroid";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";

    # The dev shells, formatter and pinned toolchains of every AnkiQuest
    # repository: JBR 21 from gradle/gradle-daemon-jvm.properties, the SDK
    # platforms and build tools of gradle/libs.versions.toml and
    # .github/workflows/tests_emulator.yml.
    ankiquest-dev = {
      url = "github:AnkiQuest/AnkiQuest-Dev";
      inputs.nixpkgs.follows = "nixpkgs";
    };
  };

  outputs = {
    nixpkgs,
    ankiquest-dev,
    ...
  }: let
    forAllSystems = nixpkgs.lib.genAttrs ankiquest-dev.lib.systems;
  in {
    inherit (ankiquest-dev) formatter;

    devShells = forAllSystems (system: {
      inherit (ankiquest-dev.devShells.${system}) android android-emulator;
      default = ankiquest-dev.devShells.${system}.android;
    });
  };
}
