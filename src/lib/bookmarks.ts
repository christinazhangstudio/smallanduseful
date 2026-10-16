export type Bookmark = {
	id: string;
	title: string;
	href: string;
	added?: number;
};

export type BookmarkFolder = {
	id: string;
	title: string;
	added?: number;
	toolbar?: boolean;
	unfiled?: boolean;
	items: Array<Bookmark | BookmarkFolder>;
};

function addedAt(value: string | null) {
	const parsed = Number(value);
	return Number.isFinite(parsed) && parsed > 0 ? parsed : undefined;
}

export function parseBookmarks(html: string): BookmarkFolder {
	const doc = new DOMParser().parseFromString(html, 'text/html');
	const root = doc.querySelector('dl');
	if (!root) throw new Error('No bookmark list found.');
	return { id: '0', title: 'Bookmarks', items: flattenToolbar(readList(root, '0')) };
}

function readList(list: Element, parentId: string): Array<Bookmark | BookmarkFolder> {
	const items: Array<Bookmark | BookmarkFolder> = [];
	for (const node of list.children) {
		if (node.tagName !== 'DT') continue;
		const id = `${parentId}/${items.length}`;
		const link = node.querySelector(':scope > a');
		if (link) {
			const href = link.getAttribute('href');
			if (!href) continue;
			items.push({
				id,
				title: link.textContent?.replace(/\s+/g, ' ').trim() || href,
				href,
				added: addedAt(link.getAttribute('add_date'))
			});
			continue;
		}
		const heading = node.querySelector(':scope > h3');
		const nested = node.querySelector(':scope > dl');
		if (!heading && !nested) continue;
		const title = heading?.textContent?.replace(/\s+/g, ' ').trim() || 'Folder';
		const kind = folderKind[title.trim().toLowerCase()];
		const toolbar = heading?.getAttribute('personal_toolbar_folder') === 'true' || kind === 'toolbar';
		items.push({
			id,
			title,
			added: addedAt(heading?.getAttribute('add_date') ?? null),
			toolbar: toolbar || undefined,
			unfiled: toolbar
				? undefined
				: heading?.getAttribute('unfiled_bookmarks_folder') === 'true' || kind === 'unfiled' || undefined,
			items: nested ? readList(nested, id) : []
		});
	}
	return items;
}

export function isFolder(item: Bookmark | BookmarkFolder): item is BookmarkFolder {
	return 'items' in item;
}

export function countBookmarks(folder: BookmarkFolder): number {
	return folder.items.reduce(
		(total, item) => total + (isFolder(item) ? countBookmarks(item) : 1),
		0
	);
}
export function removeBookmark(folder: BookmarkFolder, id: string): BookmarkFolder {
	const items: Array<Bookmark | BookmarkFolder> = [];
	for (const item of folder.items) {
		if (item.id === id) continue;
		items.push(isFolder(item) ? { ...item, items: removeBookmark(item, id).items } : item);
	}
	return { ...folder, items };
}

export function serializeBookmarks(folder: BookmarkFolder): string {
	const { bar, other } = splitForChrome(folder.items);
	const lines = [
		'<!DOCTYPE NETSCAPE-Bookmark-file-1>',
		'<META HTTP-EQUIV="Content-Type" CONTENT="text/html; charset=UTF-8">',
		'<TITLE>Bookmarks</TITLE>',
		'<H1>Bookmarks</H1>',
		'<DL><p>'
	];
	if (bar.length > 0) {
		const added = folder.added ?? Math.floor(Date.now() / 1000);
		lines.push(`    <DT><H3 ADD_DATE="${added}" PERSONAL_TOOLBAR_FOLDER="true">Bookmarks bar</H3>`);
		lines.push('    <DL><p>');
		writeList(lines, bar, '        ');
		lines.push('    </DL><p>');
	}
	writeList(lines, other, '    ');
	lines.push('</DL><p>');
	return lines.join('\n');
}

function writeList(lines: string[], items: Array<Bookmark | BookmarkFolder>, indent: string) {
	for (const item of items) {
		if (isFolder(item)) {
			const extra = item.unfiled ? ' UNFILED_BOOKMARKS_FOLDER="true"' : '';
			lines.push(`${indent}<DT><H3${dateAttr(item.added)}${extra}>${escapeHtml(item.title)}</H3>`);
			lines.push(`${indent}<DL><p>`);
			writeList(lines, item.items, `${indent}    `);
			lines.push(`${indent}</DL><p>`);
			continue;
		}
		lines.push(
			`${indent}<DT><A HREF="${escapeHtml(item.href)}"${dateAttr(item.added)}>${escapeHtml(item.title)}</A>`
		);
	}
}

function dateAttr(added: number | undefined) {
	return added ? ` ADD_DATE="${added}"` : '';
}

function escapeHtml(value: string) {
	return value.replace(/[&<>"']/g, (char) => {
		if (char === '&') return '&amp;';
		if (char === '<') return '&lt;';
		if (char === '>') return '&gt;';
		if (char === '"') return '&quot;';
		return '&#39;';
	});
}
const folderKind: Record<string, 'toolbar' | 'unfiled'> = {
	'bookmarks bar': 'toolbar',
	'bookmarks toolbar': 'toolbar',
	'favorites bar': 'toolbar',
	'favourites bar': 'toolbar',
	书签栏: 'toolbar',
	書籤列: 'toolbar',
	收藏夹栏: 'toolbar',
	收藏夾列: 'toolbar',
	'other bookmarks': 'unfiled',
	'other favorites': 'unfiled',
	'other favourites': 'unfiled',
	'unsorted bookmarks': 'unfiled',
	其他书签: 'unfiled',
	其他書籤: 'unfiled',
	其他收藏夹: 'unfiled',
	其他收藏夾: 'unfiled'
};

function flattenToolbar(items: Array<Bookmark | BookmarkFolder>) {
	const flat: Array<Bookmark | BookmarkFolder> = [];
	for (const item of items) {
		if (isFolder(item) && item.toolbar) flat.push(...item.items);
		else flat.push(item);
	}
	return flat;
}

function splitForChrome(items: Array<Bookmark | BookmarkFolder>) {
	const bar: Array<Bookmark | BookmarkFolder> = [];
	const other: BookmarkFolder[] = [];
	for (const item of items) {
		if (isFolder(item) && (item.unfiled || folderKind[item.title.trim().toLowerCase()] === 'unfiled')) {
			other.push(item.unfiled ? item : { ...item, unfiled: true });
			continue;
		}
		if (isFolder(item) && folderKind[item.title.trim().toLowerCase()] === 'toolbar') {
			bar.push(...item.items);
			continue;
		}
		bar.push(item);
	}
	return { bar, other };
}
