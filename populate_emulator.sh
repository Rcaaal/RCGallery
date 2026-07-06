#!/system/bin/sh

# Take base screenshots
for i in 1 2 3 4 5; do
    screencap -p /sdcard/DCIM/base_$i.png
done

ALBUMS="/sdcard/DCIM/测试相册1 /sdcard/DCIM/测试相册2 /sdcard/DCIM/测试相册3 /sdcard/DCIM/测试相册4 /sdcard/DCIM/测试相册5 /sdcard/DCIM/Screenshots /sdcard/Movies/视频合集"

for album in $ALBUMS; do
    echo "Populating $album"
    img_count=25
    vid_count=8

    for j in $(seq 1 $img_count); do
        base_idx=$(( (j % 5) + 1 ))
        src="/sdcard/DCIM/base_$base_idx.png"
        dst="$album/img_$(printf '%03d' $j).png"
        cp $src $dst
    done

    for j in $(seq 1 $vid_count); do
        dst="$album/vid_$(printf '%03d' $j).mp4"
        printf '\x00\x00\x00\x1c\x66\x74\x79\x70\x69\x73\x6f\x6d' > $dst
    done
done

rm /sdcard/DCIM/base_*.png
am broadcast -a android.intent.action.MEDIA_MOUNTED -d file:///sdcard
echo "DONE"
