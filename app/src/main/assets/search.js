(() => {
  const root = document.querySelector('.s-page');
  if (!root) return null;
  const links = root.querySelectorAll('.search-results .mb-3 > a.result-nav-link[href]');
  const items = new Map();
  for (const link of links) {
    const name = link.textContent.trim();
    const url = new URL(link.href);
    if (name && url.origin === location.origin && /^\/(play|watch)(\/|$)/.test(url.pathname)) {
      items.set(url.href, name);
    }
  }

  const nav = root.querySelector('.page-controls app-pager');
  const buttons = [];
  if (nav) {
    for (const element of nav.querySelectorAll('*')) {
      const label = element.textContent.trim();
      if (!/^(?:上一页|下一页)$/.test(label) || element.children.length) continue;
      const control = element.closest('button,a,[role=button],li') || element;
      buttons.push({
        label, control,
        enabled: !control.closest('[disabled],[aria-disabled=true],.disabled')
      });
    }
  }
  window.__movieTurnPage = direction => {
    const button = buttons.find(item => item.label === direction);
    if (button?.enabled) button.control.click();
  };
  const count = root.querySelector('.search-result-counter')?.textContent || '';
  return {
    items: [...items].map(([url, name]) => ({ name, url })),
    previous: buttons.some(item => item.label === '上一页' && item.enabled),
    next: buttons.some(item => item.label === '下一页' && item.enabled),
    empty: /(?:^|\D)0\s*(?:个|条)/.test(count) ||
      /未找到|没有找到|暂无搜索结果/.test(root.querySelector('.content')?.textContent || ''),
  };
})();
