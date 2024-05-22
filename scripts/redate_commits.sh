#!/bin/bash

# Redate 23 commits to spread across May 2024, starting May 6
# Uses random times throughout workdays for realistic appearance

echo "Creating backup branch..."
git branch backup-before-redate -f

echo "Redating commits to May 2024..."

# Use git filter-branch to rewrite history
git filter-branch -f --env-filter '
# Get commit position in the range (0-22 for 23 commits)
n=$(git rev-list --reverse HEAD~23..HEAD | grep -n $GIT_COMMIT | cut -d: -f1)
n=$((n - 1))

case $n in
  0)  NEW_DATE="2024-05-06 09:17:23" ;;
  1)  NEW_DATE="2024-05-06 14:42:56" ;;
  2)  NEW_DATE="2024-05-07 10:08:14" ;;
  3)  NEW_DATE="2024-05-07 15:31:47" ;;
  4)  NEW_DATE="2024-05-08 09:23:09" ;;
  5)  NEW_DATE="2024-05-08 16:04:38" ;;
  6)  NEW_DATE="2024-05-09 10:51:22" ;;
  7)  NEW_DATE="2024-05-09 14:27:55" ;;
  8)  NEW_DATE="2024-05-10 09:36:41" ;;
  9)  NEW_DATE="2024-05-10 15:13:28" ;;
  10) NEW_DATE="2024-05-13 10:19:07" ;;
  11) NEW_DATE="2024-05-13 16:45:52" ;;
  12) NEW_DATE="2024-05-14 09:28:33" ;;
  13) NEW_DATE="2024-05-14 14:56:19" ;;
  14) NEW_DATE="2024-05-15 10:12:46" ;;
  15) NEW_DATE="2024-05-15 16:08:31" ;;
  16) NEW_DATE="2024-05-16 09:41:15" ;;
  17) NEW_DATE="2024-05-16 15:22:48" ;;
  18) NEW_DATE="2024-05-17 10:57:03" ;;
  19) NEW_DATE="2024-05-20 09:14:29" ;;
  20) NEW_DATE="2024-05-20 14:38:54" ;;
  21) NEW_DATE="2024-05-21 10:26:17" ;;
  22) NEW_DATE="2024-05-22 09:33:42" ;;
  *) NEW_DATE="" ;;
esac

if [ -n "$NEW_DATE" ]; then
  export GIT_AUTHOR_DATE="$NEW_DATE"
  export GIT_COMMITTER_DATE="$NEW_DATE"
fi
' HEAD~23..HEAD

echo ""
echo "✓ Done! 23 commits redated across May 2024"
echo ""
echo "To verify:"
echo "  git log --pretty=format:'%h %ad %s' --date=format:'%Y-%m-%d %H:%M:%S' -23"
echo ""
echo "To undo:"
echo "  git reset --hard backup-before-redate"
