import { spawn } from "node:child_process";
import { existsSync } from "node:fs";
import { mkdir, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const projectRoot = path.resolve(scriptDir, "..");
const frontendUrl = process.env.FRONTEND_URL || "http://localhost:5173";
const backendUrl = process.env.BACKEND_URL || "http://localhost:8080";
const screenshotDir = path.join(projectRoot, "storage", "screenshots");
const widths = (process.env.LAYOUT_WIDTHS || "1440,1280")
  .split(",")
  .map((value) => Number(value.trim()))
  .filter(Boolean);

const chromePath = findBrowser();
const profileDir = await mkdtemp(path.join(os.tmpdir(), "football-layout-"));

let browser;
try {
  await mkdir(screenshotDir, { recursive: true });
  browser = spawn(chromePath, [
    "--headless=new",
    "--disable-gpu",
    "--no-first-run",
    "--no-default-browser-check",
    "--remote-debugging-port=0",
    `--user-data-dir=${profileDir}`,
    "about:blank",
  ], { stdio: "ignore" });

  const port = await readDevToolsPort(profileDir);
  const target = await firstPageTarget(port);
  const client = await connectCdp(target.webSocketDebuggerUrl);
  await client.send("Page.enable");
  await client.send("Runtime.enable");

  const login = await loginAsAdmin();
  const results = [];
  for (const width of widths) {
    results.push(await checkViewport(client, login, width, 900));
  }

  await client.close();
  browser.kill();

  const failed = results.some((result) =>
    result.hasHorizontalOverflow ||
    result.overflowers.length > 0 ||
    !result.hasEventDetail ||
    result.annotation.hasHorizontalOverflow ||
    result.annotation.overflowers.length > 0 ||
    !result.annotation.hasTable ||
    result.videoAnnotation.hasHorizontalOverflow ||
    result.videoAnnotation.overflowers.length > 0 ||
    !result.videoAnnotation.hasWorkspace
  );
  for (const result of results) {
    console.log(JSON.stringify(result, null, 2));
  }
  if (failed) {
    process.exitCode = 2;
  }
} finally {
  await stopBrowser(browser);
  await rm(profileDir, { recursive: true, force: true }).catch(() => {});
}

function findBrowser() {
  const candidates = [
    process.env.CHROME_PATH,
    "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe",
    "C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe",
    "C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe",
    "C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe",
  ].filter(Boolean);

  const found = candidates.find((candidate) => existsSync(candidate));
  if (!found) {
    throw new Error("Chrome or Edge was not found. Set CHROME_PATH to a Chromium-based browser executable.");
  }
  return found;
}

async function readDevToolsPort(profile) {
  const portFile = path.join(profile, "DevToolsActivePort");
  for (let attempt = 0; attempt < 100; attempt += 1) {
    if (existsSync(portFile)) {
      const [port] = (await readFile(portFile, "utf-8")).split(/\r?\n/);
      return Number(port);
    }
    await delay(100);
  }
  throw new Error("Timed out waiting for browser DevTools port.");
}

async function firstPageTarget(port) {
  for (let attempt = 0; attempt < 50; attempt += 1) {
    const targets = await fetchJson(`http://127.0.0.1:${port}/json/list`);
    const target = targets.find((item) => item.type === "page" && item.webSocketDebuggerUrl);
    if (target) {
      return target;
    }
    await delay(100);
  }
  throw new Error("Timed out waiting for a browser page target.");
}

async function fetchJson(url, options) {
  const response = await fetch(url, options);
  if (!response.ok) {
    throw new Error(`HTTP ${response.status} for ${url}`);
  }
  return response.json();
}

async function loginAsAdmin() {
  return fetchJson(`${backendUrl}/api/auth/login`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ username: "admin", password: "admin123" }),
  });
}

async function checkViewport(client, login, width, height) {
  await client.send("Emulation.setDeviceMetricsOverride", {
    width,
    height,
    deviceScaleFactor: 1,
    mobile: false,
  });

  await client.send("Page.navigate", { url: frontendUrl });
  await delay(800);
  await client.send("Runtime.evaluate", {
    expression: `
      localStorage.setItem("token", ${JSON.stringify(login.token)});
      localStorage.setItem("user", ${JSON.stringify(JSON.stringify(login.user))});
      location.reload();
    `,
  });
  await delay(1200);

  await client.send("Runtime.evaluate", {
    awaitPromise: true,
    expression: `
      (async () => {
        await new Promise((resolve) => setTimeout(resolve, 800));
        const firstTask = document.querySelector(".task-row");
        if (firstTask) firstTask.click();
        await new Promise((resolve) => setTimeout(resolve, 1200));
      })()
    `,
  });

  const evaluation = await client.send("Runtime.evaluate", {
    awaitPromise: true,
    returnByValue: true,
    expression: `
      (() => {
        const overflowers = Array.from(document.querySelectorAll("body *"))
          .filter((element) => !element.closest(".el-table__inner-wrapper"))
          .map((element) => {
            const rect = element.getBoundingClientRect();
            return {
              tag: element.tagName.toLowerCase(),
              className: String(element.className || ""),
              text: String(element.textContent || "").trim().slice(0, 40),
              left: Math.round(rect.left),
              right: Math.round(rect.right),
              width: Math.round(rect.width)
            };
          })
          .filter((item) => item.right > window.innerWidth + 1 || item.left < -1)
          .slice(0, 10);
        const detail = document.querySelector(".detail-layout");
        const table = document.querySelector(".el-table__inner-wrapper");
        return {
          viewportWidth: window.innerWidth,
          viewportHeight: window.innerHeight,
          documentScrollWidth: document.documentElement.scrollWidth,
          bodyScrollWidth: document.body.scrollWidth,
          hasHorizontalOverflow:
            document.documentElement.scrollWidth > window.innerWidth + 1 ||
            document.body.scrollWidth > window.innerWidth + 1,
          taskRows: document.querySelectorAll(".task-row").length,
          hasEventDetail: Boolean(detail),
          detailColumns: detail ? getComputedStyle(detail).gridTemplateColumns : null,
          overflowers
        };
      })()
    `,
  });

  const screenshot = await client.send("Page.captureScreenshot", {
    format: "png",
    captureBeyondViewport: false,
  });
  const screenshotPath = path.join(screenshotDir, `frontend-${width}.png`);
  await writeFile(screenshotPath, Buffer.from(screenshot.data, "base64"));

  await client.send("Runtime.evaluate", {
    awaitPromise: true,
    expression: `
      (async () => {
        const annotationButton = Array.from(document.querySelectorAll(".nav-button"))
          .find((button) => button.textContent.includes("标注管理"));
        if (annotationButton) annotationButton.click();
        await new Promise((resolve) => setTimeout(resolve, 1000));
      })()
    `,
  });

  const annotationEvaluation = await client.send("Runtime.evaluate", {
    awaitPromise: true,
    returnByValue: true,
    expression: `
      (() => {
        const overflowers = Array.from(document.querySelectorAll("body *"))
          .filter((element) => !element.closest(".el-table__inner-wrapper"))
          .map((element) => {
            const rect = element.getBoundingClientRect();
            return {
              tag: element.tagName.toLowerCase(),
              className: String(element.className || ""),
              text: String(element.textContent || "").trim().slice(0, 40),
              left: Math.round(rect.left),
              right: Math.round(rect.right),
              width: Math.round(rect.width)
            };
          })
          .filter((item) => item.right > window.innerWidth + 1 || item.left < -1)
          .slice(0, 10);
        const table = document.querySelector(".el-table__inner-wrapper");
        return {
          viewportWidth: window.innerWidth,
          viewportHeight: window.innerHeight,
          documentScrollWidth: document.documentElement.scrollWidth,
          bodyScrollWidth: document.body.scrollWidth,
          hasHorizontalOverflow:
            document.documentElement.scrollWidth > window.innerWidth + 1 ||
            document.body.scrollWidth > window.innerWidth + 1,
          hasTable: Boolean(document.querySelector(".el-table")),
          tableHasInternalScroll: table ? table.scrollWidth > table.clientWidth + 1 : false,
          rows: document.querySelectorAll(".el-table__body-wrapper tbody tr").length,
          exportButtons: Array.from(document.querySelectorAll("button"))
            .filter((button) => /JSON|CSV/.test(button.textContent)).length,
          overflowers
        };
      })()
    `,
  });

  const annotationScreenshot = await client.send("Page.captureScreenshot", {
    format: "png",
    captureBeyondViewport: false,
  });
  const annotationScreenshotPath = path.join(screenshotDir, `frontend-annotations-${width}.png`);
  await writeFile(annotationScreenshotPath, Buffer.from(annotationScreenshot.data, "base64"));

  await client.send("Runtime.evaluate", {
    awaitPromise: true,
    expression: `
      (async () => {
        const videoAnnotationButton = Array.from(document.querySelectorAll(".nav-button"))
          .find((button) => button.textContent.includes("视频标注"));
        if (videoAnnotationButton) videoAnnotationButton.click();
        await new Promise((resolve) => setTimeout(resolve, 1000));
      })()
    `,
  });

  const videoAnnotationEvaluation = await client.send("Runtime.evaluate", {
    awaitPromise: true,
    returnByValue: true,
    expression: `
      (() => {
        const overflowers = Array.from(document.querySelectorAll("body *"))
          .filter((element) => !element.closest(".el-table__inner-wrapper"))
          .map((element) => {
            const rect = element.getBoundingClientRect();
            return {
              tag: element.tagName.toLowerCase(),
              className: String(element.className || ""),
              text: String(element.textContent || "").trim().slice(0, 40),
              left: Math.round(rect.left),
              right: Math.round(rect.right),
              width: Math.round(rect.width)
            };
          })
          .filter((item) => item.right > window.innerWidth + 1 || item.left < -1)
          .slice(0, 10);
        return {
          viewportWidth: window.innerWidth,
          viewportHeight: window.innerHeight,
          documentScrollWidth: document.documentElement.scrollWidth,
          bodyScrollWidth: document.body.scrollWidth,
          hasHorizontalOverflow:
            document.documentElement.scrollWidth > window.innerWidth + 1 ||
            document.body.scrollWidth > window.innerWidth + 1,
          hasWorkspace: Boolean(document.querySelector(".annotation-workspace")),
          hasVideo: Boolean(document.querySelector(".annotation-video")),
          hasEventForm: Boolean(document.querySelector(".annotation-form-panel")),
          overflowers
        };
      })()
    `,
  });

  const videoAnnotationScreenshot = await client.send("Page.captureScreenshot", {
    format: "png",
    captureBeyondViewport: false,
  });
  const videoAnnotationScreenshotPath = path.join(screenshotDir, `frontend-video-annotation-${width}.png`);
  await writeFile(videoAnnotationScreenshotPath, Buffer.from(videoAnnotationScreenshot.data, "base64"));

  return {
    ...evaluation.result.value,
    screenshotPath,
    annotation: {
      ...annotationEvaluation.result.value,
      screenshotPath: annotationScreenshotPath,
    },
    videoAnnotation: {
      ...videoAnnotationEvaluation.result.value,
      screenshotPath: videoAnnotationScreenshotPath,
    },
  };
}

function connectCdp(url) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(url);
    let nextId = 1;
    const pending = new Map();

    ws.addEventListener("open", () => {
      resolve({
        send(method, params = {}) {
          const id = nextId;
          nextId += 1;
          ws.send(JSON.stringify({ id, method, params }));
          return new Promise((commandResolve, commandReject) => {
            pending.set(id, { resolve: commandResolve, reject: commandReject });
          });
        },
        close() {
          ws.close();
        },
      });
    });

    ws.addEventListener("message", (event) => {
      const payload = JSON.parse(typeof event.data === "string" ? event.data : Buffer.from(event.data).toString("utf-8"));
      if (!payload.id || !pending.has(payload.id)) {
        return;
      }
      const callbacks = pending.get(payload.id);
      pending.delete(payload.id);
      if (payload.error) {
        callbacks.reject(new Error(payload.error.message || JSON.stringify(payload.error)));
      } else {
        callbacks.resolve(payload.result || {});
      }
    });

    ws.addEventListener("error", reject);
  });
}

function delay(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function stopBrowser(processHandle) {
  return new Promise((resolve) => {
    if (!processHandle || processHandle.killed || processHandle.exitCode !== null) {
      resolve();
      return;
    }
    const timer = setTimeout(resolve, 1500);
    processHandle.once("exit", () => {
      clearTimeout(timer);
      resolve();
    });
    processHandle.kill();
  });
}
