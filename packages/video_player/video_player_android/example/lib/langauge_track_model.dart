import 'package:background_json_parser/background_json_parser.dart';

class LanguageTrackModel extends IBaseModel<LanguageTrackModel> {
  List<LanguageItemModel>? audio;
  List<LanguageItemModel>? video;
  List<LanguageItemModel>? text;

  LanguageTrackModel({
    this.audio,
    this.video,
    this.text,
  });

  @override
  fromJson(Map<String, dynamic> json) {
    return LanguageTrackModel(
      audio: json["Audio"] == null
          ? []
          : List<LanguageItemModel>.from(json["Audio"].map((Map<String, dynamic> x) => LanguageItemModel.fromJson(x))
              as Iterable<LanguageItemModel>),
      video: json["Video"] == null
          ? []
          : List<LanguageItemModel>.from(json["Video"].map((Map<String, dynamic> x) => LanguageItemModel.fromJson(x))
              as Iterable<LanguageItemModel>),
      text: json["Text"] == null
          ? []
          : List<LanguageItemModel>.from(json["Text"].map((Map<String, dynamic> x) => LanguageItemModel.fromJson(x))
              as Iterable<LanguageItemModel>),
    );
  }

  Map<String, dynamic> toJson() => {
        "Audio": List<dynamic>.from(audio!.map((x) => x.toJson())),
        "Video": List<dynamic>.from(video!.map((x) => x.toJson())),
        "Text": List<dynamic>.from(text!.map((x) => x.toJson())),
      };
}

class LanguageItemModel {
  String? id;
  String? label;
  String? language;

  LanguageItemModel({
    this.id,
    this.label,
    this.language,
  });

  factory LanguageItemModel.fromJson(Map<dynamic, dynamic> json) => LanguageItemModel(
        id: json["id"] as String,
        label: json["label"] as String,
        language: json["language"] as String,
      );

  Map<String, dynamic> toJson() => {
        "id": id,
        "label": label,
        "language": language,
      };
}
