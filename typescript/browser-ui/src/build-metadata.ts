export function markBundledUi(root: HTMLElement): void {
  root.dataset.cobblewareUi = 'bundled';
}

// The verifier asserts that this unused export is removed even though this module is reached.
export const UNUSED_BROWSER_UI_TREE_SHAKING_SENTINEL = 'UNUSED_BROWSER_UI_TREE_SHAKING_SENTINEL';
