'use strict';
// Writes OnSpot's icon, name and version into OnSpot.exe.
//
// electron-builder normally does this itself ("signAndEditExecutable"), but on this machine its
// winCodeSign download can't be unpacked (the archive contains symlinks that need admin rights),
// so that step is turned off. Without it the exe keeps Electron's default icon and shows as
// "Electron / GitHub, Inc." in the taskbar, Start menu and Windows Firewall prompt.
const path = require('path');
const { execFileSync } = require('child_process');

exports.default = async function afterPack(context) {
  if (context.electronPlatformName !== 'win32') return;
  const { productFilename, info } = context.packager.appInfo
    ? { productFilename: context.packager.appInfo.productFilename, info: context.packager.appInfo }
    : { productFilename: 'OnSpot', info: {} };
  const exe = path.join(context.appOutDir, `${productFilename}.exe`);
  const root = context.packager.projectDir;
  const rcedit = path.join(root, 'node_modules', 'rcedit', 'bin', 'rcedit-x64.exe');
  const icon = path.join(root, 'build', 'icon.ico');
  const version = info.version || '0.0.0';
  const fileVersion = /^\d+\.\d+\.\d+$/.test(version) ? `${version}.0` : version;
  const name = info.productName || 'OnSpot';

  execFileSync(rcedit, [
    exe,
    '--set-icon', icon,
    '--set-file-version', fileVersion,
    '--set-product-version', fileVersion,
    '--set-version-string', 'FileDescription', name,
    '--set-version-string', 'ProductName', name,
    '--set-version-string', 'InternalName', name,
    '--set-version-string', 'OriginalFilename', `${productFilename}.exe`,
    '--set-version-string', 'CompanyName', 'Beyond Zero',
    '--set-version-string', 'LegalCopyright', `© ${new Date().getFullYear()} Beyond Zero`,
  ], { stdio: 'inherit' });
  console.log(`  • branded ${path.basename(exe)} with OnSpot icon and name (v${fileVersion})`);
};
