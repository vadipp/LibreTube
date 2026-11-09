import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';

// Read Gradle's actual output metadata rather than guessing the app version.
const input = 'app/build/outputs/apk/debug';
const metadata = JSON.parse(fs.readFileSync(path.join(input, 'output-metadata.json'), 'utf8'));
if (metadata.elements.length !== 1) throw new Error('Expected one universal debug APK');
const apk = metadata.elements[0];
if (!/^[0-9A-Za-z.+_-]+$/.test(apk.versionName)) throw new Error('Invalid version name');
if (!Number.isSafeInteger(apk.versionCode) || apk.versionCode <= 72) throw new Error('Missing CI version code');
const branch = process.env.GITHUB_REF_NAME.replace(/[^0-9A-Za-z._-]+/g, '-');
const sha = process.env.GITHUB_SHA.slice(0, 8);
const filename = `LibreTube-${apk.versionName}-${branch}-${sha}.apk`;
const output = path.join('dist', filename);
fs.mkdirSync('dist', { recursive: true });
fs.copyFileSync(path.join(input, apk.outputFile), output);
const size = fs.statSync(output).size;
const hash = crypto.createHash('sha256').update(fs.readFileSync(output)).digest('hex');
fs.writeFileSync(`${output}.sha256`, `${hash}  ${filename}\n`);
fs.appendFileSync(process.env.GITHUB_OUTPUT, `apk=${output}\nversion=${apk.versionName}\n`);
fs.appendFileSync(process.env.GITHUB_STEP_SUMMARY,
  `### APK\n\n${filename}\n\nVersion code: ${apk.versionCode}; bytes: ${size}\n\nSHA-256: ${hash}\n`);
console.log(`${filename}: ${size} bytes; SHA-256 ${hash}`);
