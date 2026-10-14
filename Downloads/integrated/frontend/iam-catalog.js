/** Load every IAM directory page so older identities remain selectable for access requests. */
export async function loadUserCatalogue(request) {
  const users = new Map();
  const size = 100;
  for (let page = 0; ; page++) {
    const result = await request(`/iam/users?page=${page}&size=${size}`);
    for (const user of result.items) users.set(user.userId, user);
    if ((page + 1) * size >= result.total || result.items.length === 0) break;
  }
  return [...users.values()];
}
