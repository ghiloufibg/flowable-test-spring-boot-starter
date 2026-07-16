package com.flowabletest.debugui;

/**
 * Shared page chrome for every HTML page this module serves: the {@code <style>} block (a palette
 * lifted from Flowable's own published {@code --flw-*} theming variables, see
 * https://documentation.flowable.com/latest/develop/fe/user-themes, so the debug UI reads as part
 * of the Flowable product family instead of a generic internal tool) plus the top bar markup both
 * pages wrap their content in.
 */
final class Layout {

  private Layout() {}

  static final String STYLE =
      """
      <style>
        :root {
          --flw-switcher-base: #111e2c;
          --flw-subMenu-bgcolor: #0b0f18;
          --flw-teal: #1d5d6d;
          --flw-teal-hover: #226e81;
          --flw-red: #b03b39;
          --flw-red-hover: #e54d42;
          --flw-text: #333333;
          --flw-text-secondary: #666666;
          --flw-text-muted: #999999;
          --flw-border: #e5e5e5;
          --flw-bg-muted: #f8f8f8;
          --flw-success: #32d296;
          --flw-success-bg: #edfbf6;
          --flw-error: #f0506e;
          --flw-error-bg: #fef4f6;
          --flw-orange: #faa05a;
          --flw-orange-bg: #fef5ee;
          --flw-blue-bg: #d8eafc;
        }
        * { box-sizing: border-box; }
        html, body { height: 100%; }
        body {
          margin: 0;
          font-family: "Roboto", "Segoe UI", Helvetica, Arial, sans-serif;
          background: var(--flw-bg-muted);
          color: var(--flw-text);
          font-size: 14px;
        }
        a { color: var(--flw-teal); text-decoration: none; }
        a:hover { color: var(--flw-teal-hover); text-decoration: underline; }
        code, .flw-mono { font-family: "Roboto Mono", Consolas, monospace; }

        .flw-topbar {
          background: var(--flw-switcher-base);
          color: #fff;
          padding: 14px 28px;
          display: flex;
          align-items: center;
          justify-content: space-between;
          flex-wrap: wrap;
          gap: 8px 16px;
          box-shadow: 0 1px 3px rgba(0, 0, 0, .35);
        }
        .flw-brand { display: flex; align-items: center; gap: 10px; font-size: 16px; }
        .flw-brand-mark {
          display: inline-flex; align-items: center; justify-content: center;
          width: 28px; height: 28px; border-radius: 6px;
          background: var(--flw-teal); color: #fff; font-weight: 700; font-size: 12px;
        }
        .flw-topbar-meta { color: #b0b8bf; font-size: 13px; }
        .flw-topbar a { color: #ccd1d6; }
        .flw-topbar a:hover { color: #fff; }

        .flw-main { width: 100%; padding: 24px 32px 64px; }
        .flw-page-header h1 { margin: 0 0 4px; font-size: 22px; }
        .flw-subtitle { margin: 0 0 20px; color: var(--flw-text-secondary); }

        .flw-card {
          background: #fff;
          border: 1px solid var(--flw-border);
          border-radius: 8px;
          box-shadow: 0 1px 2px rgba(0, 0, 0, .06);
        }
        .flw-toolbar { margin-bottom: 14px; }
        input[type="search"] {
          width: 100%;
          padding: 9px 12px;
          border: 1px solid var(--flw-border);
          border-radius: 6px;
          font-size: 14px;
          font-family: inherit;
        }
        input[type="search"]:focus { outline: none; border-color: var(--flw-teal); }

        button, .flw-btn {
          font-family: inherit;
          font-size: 13px;
          padding: 6px 12px;
          border-radius: 5px;
          border: 1px solid var(--flw-border);
          background: #e1e1e1;
          color: #333;
          cursor: pointer;
        }
        button:hover { background: #d4d4d4; }
        .flw-btn-primary { background: var(--flw-teal); color: #fff; border-color: var(--flw-teal); }
        .flw-btn-primary:hover { background: var(--flw-teal-hover); }

        .flw-badge {
          display: inline-flex; align-items: center; gap: 5px;
          padding: 3px 10px; border-radius: 999px;
          font-size: 12px; font-weight: 600; letter-spacing: .02em;
        }
        .flw-badge-active { background: var(--flw-success-bg); color: #1a8f68; }
        .flw-badge-ended { background: var(--flw-bg-muted); color: var(--flw-text-muted); border: 1px solid var(--flw-border); }
        .flw-badge-error { background: var(--flw-error-bg); color: var(--flw-red); }
        .flw-badge-neutral { background: var(--flw-blue-bg); color: #1d5d6d; }
        .flw-failedjob-banner { border: none; cursor: pointer; }
        .flw-failedjob-banner:hover { filter: brightness(0.95); }

        table { width: 100%; border-collapse: collapse; }
        th, td { text-align: left; padding: 9px 12px; border-bottom: 1px solid var(--flw-border); font-size: 13px; }
        th { color: var(--flw-text-secondary); font-weight: 600; text-transform: uppercase; font-size: 11px; letter-spacing: .04em; }
        tbody tr:hover { background: var(--flw-bg-muted); }

        .flw-empty-state {
          padding: 32px; text-align: center; color: var(--flw-text-muted); font-style: italic;
        }

        .flw-instance-group { margin-bottom: 16px; }
        .flw-instance-group:last-child { margin-bottom: 0; }
        .flw-instance-group-header {
          display: flex; align-items: center; justify-content: space-between;
          padding: 12px 16px; border-bottom: 1px solid var(--flw-border);
          font-weight: 600; font-size: 13px;
        }
        .flw-instance-group-count {
          color: var(--flw-text-muted); font-weight: 400; font-size: 12px;
        }
        .flw-instance-list { list-style: none; margin: 0; padding: 0; }
        .flw-instance-row { border-bottom: 1px solid var(--flw-border); }
        .flw-instance-row:last-child { border-bottom: none; }
        .flw-instance-link {
          display: flex; align-items: center; gap: 10px;
          padding: 13px 16px; color: var(--flw-text);
        }
        .flw-instance-link:hover { background: var(--flw-bg-muted); text-decoration: none; }

        details > summary { cursor: pointer; color: var(--flw-teal); font-size: 13px; }
        pre {
          background: #0b0f18; color: #d4e0e6; padding: 12px; border-radius: 6px;
          overflow-x: auto; font-size: 12px; line-height: 1.5;
        }

        .flw-breadcrumb-row {
          display: flex; align-items: center; justify-content: space-between; margin-bottom: 16px;
        }
        .flw-breadcrumb { margin: 0; font-size: 13px; }
        .flw-detail-header { padding: 20px 24px; margin-bottom: 20px; }
        .flw-detail-title { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
        .flw-detail-title h1 { margin: 0; font-size: 20px; }
        .flw-version { color: var(--flw-text-muted); font-weight: 400; font-size: 15px; }
        .flw-meta-row { display: flex; gap: 32px; flex-wrap: wrap; margin-top: 16px; }
        .flw-meta-label {
          display: block; font-size: 11px; text-transform: uppercase; letter-spacing: .04em;
          color: var(--flw-text-muted); margin-bottom: 3px;
        }
        .flw-meta-value { display: flex; align-items: center; gap: 8px; }
        .flw-meta-value button { padding: 2px 8px; font-size: 11px; }
        .flw-current-activities { display: flex; gap: 8px; flex-wrap: wrap; margin-top: 14px; }
        .flw-detail-subtitle { margin: 6px 0 0; color: var(--flw-text-secondary); font-size: 13px; }
        .flw-lineage { margin-top: 14px; font-size: 13px; }
        .flw-lineage-row { color: var(--flw-text-secondary); margin-top: 4px; }
        .flw-definition-source { max-height: 480px; overflow-y: auto; white-space: pre-wrap; text-align: left; }

        .flw-refresh-indicator {
          display: flex; align-items: center; gap: 8px; font-size: 12px; color: #b0b8bf;
        }
        .flw-refresh-indicator button { padding: 2px 10px; font-size: 11px; }

        .flw-tabs {
          display: flex; gap: 2px; border-bottom: 1px solid var(--flw-border); margin-bottom: 18px;
          overflow-x: auto;
        }
        .flw-tab-btn {
          background: none; border: none; border-bottom: 3px solid transparent;
          padding: 10px 16px; font-size: 13px; font-weight: 600; color: var(--flw-text-secondary);
          border-radius: 0; cursor: pointer; white-space: nowrap; flex-shrink: 0;
        }
        .flw-tab-btn:hover { background: var(--flw-bg-muted); color: var(--flw-text); }
        .flw-tab-btn-active { color: var(--flw-teal); border-bottom-color: var(--flw-teal); }
        .flw-tab-count {
          display: inline-block; margin-left: 5px; padding: 1px 6px; border-radius: 999px;
          background: var(--flw-bg-muted); color: var(--flw-text-muted); font-size: 11px;
        }
        .flw-tab-btn-active .flw-tab-count { background: var(--flw-blue-bg); color: var(--flw-teal); }

        .flw-diagram-card { padding: 20px; text-align: center; }
        .flw-diagram-img { max-width: 100%; cursor: zoom-in; border-radius: 4px; }
        .flw-diagram-hint { color: var(--flw-text-muted); font-size: 12px; margin: 10px 0 0; }
        .flw-lightbox {
          display: none; position: fixed; inset: 0; background: rgba(11, 15, 24, .85);
          align-items: center; justify-content: center; cursor: zoom-out; z-index: 100; padding: 40px;
        }
        .flw-lightbox-open { display: flex; }
        .flw-lightbox img { max-width: 100%; max-height: 100%; box-shadow: 0 4px 24px rgba(0, 0, 0, .5); }

        .flw-gateway-card { padding: 14px 16px; border-bottom: 1px solid var(--flw-border); }
        .flw-gateway-card:last-child { border-bottom: none; }
        .flw-gateway-header { font-weight: 600; display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }
        .flw-gateway-flows { list-style: none; margin: 0; padding: 0; font-size: 13px; }
        .flw-gateway-flows li { padding: 3px 0; }
        .flw-gateway-flow-taken { color: #1a8f68; font-weight: 600; }
        .flw-gateway-flow-not-taken { color: var(--flw-text-muted); }

        .flw-task-card, .flw-failedjob-card {
          padding: 14px 16px; border-bottom: 1px solid var(--flw-border);
        }
        .flw-task-card:last-child, .flw-failedjob-card:last-child { border-bottom: none; }
        .flw-task-name { font-weight: 600; margin-bottom: 6px; }
        .flw-failedjob-card { border-left: 3px solid var(--flw-error); }
        .flw-failedjob-header { display: flex; align-items: center; gap: 10px; margin-bottom: 6px; }
        .flw-failedjob-message { color: var(--flw-red); font-size: 13px; margin-bottom: 8px; }

        .flw-timeline { list-style: none; margin: 0; padding: 20px 24px; }
        .flw-timeline-item { position: relative; padding: 0 0 20px 24px; border-left: 2px solid var(--flw-border); }
        .flw-timeline-item:last-child { border-left-color: transparent; padding-bottom: 0; }
        .flw-timeline-dot {
          position: absolute; left: -6px; top: 2px; width: 10px; height: 10px; border-radius: 50%;
          background: var(--flw-teal); border: 2px solid #fff; box-shadow: 0 0 0 1px var(--flw-teal);
        }
        .flw-timeline-dot-running { background: var(--flw-orange); box-shadow: 0 0 0 1px var(--flw-orange); }
        .flw-timeline-name { font-weight: 600; }
        .flw-timeline-meta { color: var(--flw-text-muted); font-size: 12px; margin-top: 2px; }

        .flw-toast {
          position: fixed; left: 50%; bottom: 28px; transform: translate(-50%, 12px);
          background: var(--flw-switcher-base); color: #fff; padding: 10px 18px; border-radius: 6px;
          font-size: 13px; box-shadow: 0 4px 16px rgba(0, 0, 0, .3);
          opacity: 0; pointer-events: none; transition: opacity .2s ease, transform .2s ease;
          z-index: 200;
        }
        .flw-toast-visible { opacity: 1; transform: translate(-50%, 0); }
      </style>
      """;

  static String topBar(String rightText) {
    return """
        <header class="flw-topbar">
          <div class="flw-brand">
            <span class="flw-brand-mark">FT</span>
            <span>Flowable Test &middot; <strong>Debug UI</strong></span>
          </div>
          <span class="flw-topbar-meta">%s</span>
        </header>
        """
        .formatted(rightText);
  }
}
