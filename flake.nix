{
  description = "HEX local-first voice dictation";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    home-manager = {
      url = "github:nix-community/home-manager";
      inputs.nixpkgs.follows = "nixpkgs";
    };
  };

  outputs =
    { self, nixpkgs, ... }:
    let
      systems = [
        "x86_64-linux"
        "aarch64-linux"
      ];
      forSystems = f: nixpkgs.lib.genAttrs systems (system: f nixpkgs.legacyPackages.${system});
      package = pkgs: self.packages.${pkgs.stdenv.hostPlatform.system}.hex;
    in
    {
      packages = forSystems (
        pkgs:
        let
          hex = pkgs.callPackage ./nix/package.nix { };
        in
        {
          inherit hex;
          default = hex;
        }
      );

      overlays.default = final: _prev: {
        hex = final.callPackage ./nix/package.nix { };
      };

      nixosModules.hex =
        { pkgs, ... }:
        {
          imports = [ ./nix/module.nix ];
          programs.hex.package = nixpkgs.lib.mkDefault (package pkgs);
        };
      nixosModules.default = self.nixosModules.hex;

      homeManagerModules.hex =
        { pkgs, ... }:
        {
          imports = [ ./nix/hm-module.nix ];
          programs.hex.package = nixpkgs.lib.mkDefault (package pkgs);
        };
      homeManagerModules.default = self.homeManagerModules.hex;

      devShells = forSystems (
        pkgs:
        {
          default = (package pkgs).devShell;
        }
        // nixpkgs.lib.optionalAttrs (pkgs.stdenv.hostPlatform.system == "x86_64-linux") (
          let
            androidPkgs = import nixpkgs {
              inherit (pkgs.stdenv.hostPlatform) system;
              config = {
                allowUnfree = true;
                android_sdk.accept_license = true;
              };
            };
            android = androidPkgs.androidenv.composeAndroidPackages {
              toolsVersion = null;
              platformVersions = [ "36" ];
              buildToolsVersions = [
                "35.0.0"
                "36.0.0"
              ];
              includeNDK = true;
              ndkVersions = [ "27.2.12479018" ];
              cmakeVersions = [ "3.22.1" ];
            };
            sdk = "${android.androidsdk}/libexec/android-sdk";
          in
          {
            android = pkgs.mkShellNoCC {
              packages = [
                android.androidsdk
                android.platform-tools
                pkgs.jdk21_headless
                pkgs.patch
              ];
              JAVA_HOME = pkgs.jdk21_headless.home;
              ANDROID_HOME = sdk;
              ANDROID_NDK_ROOT = "${sdk}/ndk/27.2.12479018";
              shellHook = ''
                export PATH="${sdk}/cmake/3.22.1/bin:$PATH"
                export GRADLE_OPTS="''${GRADLE_OPTS:+$GRADLE_OPTS }-Dorg.gradle.project.android.aapt2FromMavenOverride=${sdk}/build-tools/36.0.0/aapt2"
              '';
            };
          }
        )
      );
      checks = forSystems (pkgs: import ./nix/checks.nix { inherit self pkgs; });
      formatter = forSystems (pkgs: pkgs.nixfmt);
    };
}
